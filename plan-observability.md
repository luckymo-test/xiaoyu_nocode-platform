# 计划书：AI 调用可观测性（Prometheus + Grafana）

> 日期：2026-09-06
> 目标：把 AI 模型调用做成可观测——指标上报 Prometheus、Grafana 出趋势/性能/Token 面板，并支持"热门应用/活跃用户"排行。
> 现状：Spring Boot 3.5.15 + Spring AI 1.1.8；Micrometer 仅作为传递依赖存在（`micrometer-observation`），**pom 里没有 actuator / prometheus registry**，也没有任何自定义指标；AI 调用集中在 `ai/VueProjectAgent.runToolLoop` 的 `streamAndAggregate` + `accumulateUsage`。

---

## 一、最关键的设计决策：维度不能都塞进 Prometheus

你列的 9 个维度里，`userId`、`appId`、**AI 回复消息内容**、`错误信息` 都是**无界高基数**。Prometheus 的标签（label）会为每个取值组合建独立时间序列——把消息正文或错误堆栈当标签，**几万条时序瞬间把 Prometheus 打爆（OOM）**。这是可观测性里最经典的坑。

所以按"数据形态三分"：

| 用途 | 载体 | 放什么 |
|---|---|---|
| **聚合指标**（趋势/分位/消耗） | **Prometheus**（→Grafana） | 只放**低基数**标签：model、调用类型、status、归一化 finish_reason / error_type |
| **明细与排行**（谁在调、排行、内容回看） | **MySQL**（新表 `ai_call_log`，Grafana 用 MySQL 数据源） | userId、appId、message 内容、error 详情、逐次 token/时长 |
| **日志**（排障、串联） | 现有日志（可选 Loki + traceId） | 结构化字段，便于按 userId/appId 检索 |

> 结论：**Prometheus 负责"量"，MySQL 明细负责"谁"和"内容"**。两者都能接进 Grafana 统一出图。

### 维度 → 落点映射

| 维度 | Prometheus 标签 | MySQL 明细列 | 说明 |
|---|:--:|:--:|---|
| 用户 ID | ✕ | ✓ | 高基数，只进明细/排行 |
| 应用 ID | ✕ | ✓ | 同上 |
| 模型名称 | ✓ | ✓ | 低基数（当前就 1 个） |
| maxOutputTokens | ✓(可选) | ✓ | 配置值，基数极低 |
| AI 回复消息内容 | ✕ | ✓ | 大文本，绝不进标签 |
| finishReason | ✓(归一化) | ✓ | 归一到 stop/length/tool_calls/error 几个值 |
| 调用状态 | ✓ | ✓ | success / error |
| 请求时间 | 天然(抓取时刻) | ✓(createTime) | |
| 错误信息 | ✕(仅 error_type 归一化) | ✓(原始) | 原始文本只进明细/日志 |

---

## 二、指标设计（Prometheus）

指标命名遵循 `ai_<对象>_<单位>`，标签只保留低基数：

| 指标名 | 类型 | 标签 | 对应你列的指标 |
|---|---|---|---|
| `ai_call_total` | Counter | `model, call_type(html/multi_file/vue/modify), status` | 调用次数（趋势/排行基数） |
| `ai_call_duration_seconds` | Histogram | `model, call_type` | 响应时长（含 P50/P90/P95/P99） |
| `ai_tokens_total` | Counter | `model, call_type, kind(input/output)` | 输入/输出 Token（总=求和） |
| `ai_call_errors_total` | Counter | `model, call_type, error_type(network/truncation/rate_limit/auth/other)` | 失败按原因分类 |

- **总 Token** = `ai_tokens_total{kind="input"}` + `{kind="output"}`；也可以在埋点侧再打一个 `kind="total"` 方便直接查。
- `duration` 用 **Histogram**（不是 Summary）：Histogram 才能跨实例聚合算分位数，Summary 不能。
- 分位数桶按 AI 特征配（几秒~几分钟），默认桶粒度不够，需要自定义 `SLO`/`distribution` 桶（如 .5,1,2,5,10,30,60,120s）。

## 三、埋点实现

**依赖**（pom）：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`。

**配置**（application.yml）：
```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  metrics.tags.application: xiaoyu-zerocode   # 全局标签
  # 可选：metrics 端口与业务端口分离
```

**新增 `core/AiCallRecorder`（或 ai/AiCallMetrics）**，注入 `MeterRegistry` + `AiCallLogMapper`：
- `recordSuccess(model, callType, appId, userId, maxOutputTokens, finishReason, inTokens, outTokens, durationMs, content)` → 打 Counter/Timer/Histogram + 异步写 `ai_call_log`；
- `recordFailure(model, callType, appId, userId, errorType, errorMsg, durationMs)` → 打失败 Counter（按归一化 error_type）+ 写明细。

**接入点**：`VueProjectAgent.runToolLoop` 已能拿到 usage 与耗时（现有打点日志），在**每次 `chatModel.stream` 前后包一层计时**，成功/失败分支分别调 `recordSuccess/recordFailure`。`appId` 现成；`userId` 由门面/Controller 传入（或 record 时按 appId 查一次 app 拿 owner）。`model` 从配置读（`deepseek-v4-flash`）。

**明细表 `ai_call_log`**（MyBatis-Flex 实体 + Mapper，仿现有 `app_image`）：
`id, appId, userId, model, maxOutputTokens, callType, inputTokens, outputTokens, totalTokens, durationMs, finishReason, status, errorMsg, assistantContent(截断), createTime` + 索引(appId,createTime)/(userId,createTime)。
> `assistantContent` 是"AI 回复消息内容"的落点；若不希望存正文，只存 `chat_history` 关联 id 亦可（注意隐私与体积）。

## 四、部署与采集

- **docker-compose**：`prometheus`（scrape `host.docker.internal:8124/actuator/prometheus`）+ `grafana`。
- Grafana 数据源：`Prometheus`（指标面板）+ `MySQL`（明细/排行面板）。
- 面板与配置**代码化**（provisioning：datasources + dashboards as JSON）放进仓库，便于复现。
- 保留：Prometheus retention 默认 15d；`ai_call_log` 定期清理（或按时间分区）。

## 五、分析能力 → PromQL / SQL 对照

**1. 模型调用分析（趋势）**
```promql
sum(increase(ai_call_total[5m])) by (model, call_type)
# 按小时/天把 [5m] 换成 [1h]/[1d]
```

**2. 模型性能分析（P50/P90/P95/P99）**
```promql
histogram_quantile(0.99, sum(rate(ai_call_duration_seconds_bucket[5m])) by (le, model))
# P50/P90/P95 同理改 0.5/0.9/0.95
```

**3. Token 消耗分析**
```promql
sum(increase(ai_tokens_total{kind="input"}[1h]))  by (model)
sum(increase(ai_tokens_total{kind="output"}[1h])) by (model)
# 总消耗 = 两者相加（或直接用 kind="total"）
```

**4. 热门应用排行 / 5. 用户活跃排行** —— 走 **MySQL 明细**（高基数，Prometheus 不适合）：
```sql
-- 热门应用榜
SELECT appId, COUNT(*) AS calls, SUM(totalTokens) AS tokens
FROM ai_call_log WHERE createTime >= ? GROUP BY appId ORDER BY calls DESC LIMIT 20;
-- 用户活跃榜
SELECT userId, COUNT(*) AS calls, SUM(totalTokens) AS tokens
FROM ai_call_log WHERE createTime >= ? GROUP BY userId ORDER BY calls DESC LIMIT 20;
```
Grafana 用 MySQL 数据源 + Table/Bar 面板展示即可（也能带出应用名/用户名关联）。

## 六、分阶段实施

| 阶段 | 内容 | 工作量 | 验证 |
|---|---|---|---|
| 1 | pom 加 actuator+prometheus；yml 开端点；`AiCallRecorder` + 埋点 | ★★ | `curl /actuator/prometheus` 能看到 `ai_*` 指标且随调用变化 |
| 2 | docker-compose 起 Prometheus+Grafana；接数据源；出 3 张指标面板（趋势/性能/Token） | ★★ | 面板曲线随生成任务变动 |
| 3 | `ai_call_log` 表 + 成功/失败明细落库（异步）；MySQL 数据源 + 2 张排行面板 | ★★★ | 跑几次生成后排行/明细正确 |
| 4 | 告警（失败率、P99 超阈、Token 突增）+（可选）Loki 日志与 traceId 串联 | ★★ | 手动造错触发告警 |
| 5 | 收尾：面板/数据源 provision 入库、明细清理策略、文档 | ★ | 按 compose 一键复现 |

## 七、实施细化（按 8 步拆解）

> 关键前提：`model=deepseek-v4-flash`、`maxOutputTokens=32768` 都在 `application.yml` 的 `spring.ai.openai.chat.options.*` 里，是**启动期常量**，直接 `@Value` 注入即可，不用每轮传；真正需要"带上"的只有 `appId`（已有）、`userId`（按 appId 解析）、`callType`（由入口决定）、`startTime`（调用点取）。

### 1. 引入依赖（pom.xml）
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```
> 当前项目没有这两个（Micrometer 只是 Spring AI 带来的传递依赖），加完才有 `/actuator/prometheus`。

### 2. 编写配置（application.yml）
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
  metrics:
    tags:
      application: xiaoyu-zerocode   # 全局标签，多实例时便于区分
    distribution:
      # Histogram 桶：AI 调用耗时跨度大（秒~分钟），默认桶不够，需自定义
      slo: 500ms,1s,2s,5s,10s,30s,60s,120s
      percentiles-histogram:
        ai.call.duration: true       # 打开后 Prometheus 才有 _bucket 供 histogram_quantile
```
> 也可分离端口：`management.server.port: 8125`，让指标端点不走公网业务端口（更安全）。

### 3. 监控上下文（新增 `ai/AiCallContext`）
贯通"这一轮生成/修改是谁、什么类型、什么时候开始"的上下文；**显式传参**而非 ThreadLocal（生成跑在线程池、工具还可能在框架内部线程执行，ThreadLocal 易错绑）。
```java
public record AiCallContext(Long appId, Long userId, String callType, long startMillis) {
    public static AiCallContext of(Long appId, Long userId, String callType) {
        return new AiCallContext(appId, userId, callType, System.currentTimeMillis());
    }
}
```
- **产生**：门面（`AiCodeGeneratorFacade`）在进入生成/修改前构造；`userId` 用 `appService.getById(appId).getUserId()` 解析一次（controller 有 loginUser 也可直接传）。
- **传递**：作为参数加进 `VueProjectAgent.generate / fixBuildError / modifyGeneratedSite → runToolLoop`（改动小、路径清晰）。
- `callType`：html / multi_file / vue / modify / fix。

### 4. 指标收集器（新增 `core/AiMetricsRecorder`）
注入 `MeterRegistry`，只做"打点"，保持低基数：
```java
@Component
public class AiMetricsRecorder {
    private final MeterRegistry registry; // 构造注入
    private static final String MODEL = "deepseek-v4-flash"; // 或 @Value 注入配置里的 model 名

    // 调用结束（成功/失败都要调用一次）
    public void record(String callType, String status, String finishReason,
                       String errorType, long inTokens, long outTokens, long durationMs) {
        Counter.builder("ai_call_total")
            .tag("model", MODEL).tag("call_type", callType).tag("status", status)
            .register(registry).increment();

        Timer.builder("ai_call_duration_seconds")
            .tag("model", MODEL).tag("call_type", callType)
            .publishPercentileHistogram()          // 配合 yml 的 percentiles-histogram
            .register(registry).record(Duration.ofMillis(durationMs));

        if (inTokens > 0)  tokens("input", callType, inTokens);
        if (outTokens > 0) tokens("output", callType, outTokens);

        if ("error".equals(status) && errorType != null) {
            Counter.builder("ai_call_errors_total")
                .tag("model", MODEL).tag("call_type", callType).tag("error_type", errorType)
                .register(registry).increment();
        }
    }
    private void tokens(String kind, String callType, long v) {
        Counter.builder("ai_tokens_total").tag("model", MODEL)
            .tag("call_type", callType).tag("kind", kind)
            .register(registry).increment(v);
    }
    // errorType 归一化：把异常文本映射到 network/truncation/rate_limit/auth/other（保持低基数）
    public static String classify(Throwable e) { /* 关键字匹配，返回枚举值 */ }
}
```

### 5. AI 调用监听器（新增 `ai/AiCallListener`）
在**每次模型调用**处做"计时 + 记录 + 异步落明细"，与指标收集器解耦：
```java
@Component
public class AiCallListener {
    private final AiMetricsRecorder metrics;
    private final AiCallLogMapper logMapper;      // MyBatis-Flex，仿 app_image
    // 明细落库走异步，绝不阻塞主链路
    private final ExecutorService detailWriter = Executors.newVirtualThreadPerTaskExecutor();

    public void onSuccess(AiCallContext ctx, String finishReason, long in, long out, long ms, String content) {
        metrics.record(ctx.callType(), "success", finishReason, null, in, out, ms);
        detailWriter.submit(() -> logMapper.insert(AiCallLog.of(ctx, in, out, ms, finishReason, "success", null, content)));
    }
    public void onFailure(AiCallContext ctx, Throwable e, long ms, String content) {
        metrics.record(ctx.callType(), "error", null, AiMetricsRecorder.classify(e), 0, 0, ms);
        detailWriter.submit(() -> logMapper.insert(AiCallLog.of(ctx, 0, 0, ms, null, "error", e.getMessage(), content)));
    }
}
```
**接线点**：`VueProjectAgent.runToolLoop` 里每次 `streamAndAggregate(...)` 前后：
```java
long t0 = System.currentTimeMillis();
try {
    resp = streamAndAggregate(prompt, reasoningSink);   // 现有代码
    // 现有 accumulateUsage(resp, usage) 已拿到 usage
    listener.onSuccess(ctx, finishReasonOf(resp), usage[0], usage[1], System.currentTimeMillis()-t0, text);
} catch (RuntimeException e) {
    listener.onFailure(ctx, e, System.currentTimeMillis()-t0, null);   // 现有截断重试逻辑保留
    throw/t重试...
}
```
> 明细表 `ai_call_log` 实体 + Mapper（MyBatis-Flex），字段见第三节；`assistantContent` 截断存，或只存 `chat_history` 关联 id。

### 6. 测试验证
- **端点**：`curl localhost:8124/actuator/prometheus | grep ai_` 能看到 `ai_call_total` 等；跑一次生成后再看计数/桶是否变化。
- **单测**：用 `SimpleMeterRegistry` 注入 `AiMetricsRecorder`，断言 counter/histogram 计数与 tag（不依赖 Spring 上下文）。
- **集成冒烟**：调一次生成 → 断言 `ai_call_total{status="success"}` +1、`ai_call_duration_seconds_count` +1、`ai_tokens_total` 增长；`ai_call_log` 新增一行且 appId/userId/token 正确；再故意让模型报错（如临时改坏 key）→ `status="error"` 与 `error_type` 落点正确。

### 7. Prometheus 配置
`deploy/prometheus/prometheus.yml`：
```yaml
scrape_configs:
  - job_name: xiaoyu-zerocode
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8124"]   # 容器访问宿主机（Mac/Win）；Linux 用宿主机 IP
    scrape_interval: 15s
```
`deploy/docker-compose.yml`：起 `prometheus`（挂上面的 yml）+ `grafana`（挂 provisioning）。

### 8. Grafana 可视化配置（provisioning 代码化）
```
deploy/grafana/provisioning/
  datasources/datasources.yml   # Prometheus(默认) + MySQL(明细)
  dashboards/dashboards.yml      # 指向下面 json
  dashboards/ai-observability.json
```
面板（`ai-observability.json`）：
| 面板 | 数据源 | 查询 |
|---|---|---|
| 调用趋势（分模型/类型） | Prom | `sum(increase(ai_call_total[5m])) by (model, call_type)` |
| 成功率 / 失败率 | Prom | `sum(rate(ai_call_total{status="success"}[5m])) / sum(rate(ai_call_total[5m]))` |
| 响应时长 P50/P90/P95/P99 | Prom | `histogram_quantile(0.99, sum(rate(ai_call_duration_seconds_bucket[5m])) by (le))` |
| Token 消耗（输入/输出/总） | Prom | `sum(increase(ai_tokens_total[1h])) by (kind)` |
| 失败原因分布 | Prom | `sum(increase(ai_call_errors_total[1h])) by (error_type)` |
| 热门应用排行 | MySQL | `SELECT appId, COUNT(*), SUM(totalTokens) … GROUP BY appId ORDER BY 2 DESC` |
| 用户活跃排行 | MySQL | 同上换 userId |
```
MySQL 数据源配置里指向 xiaoyu_zerocode 库；排行面板用 Table/Bar。

---

## 八、与面试/简历的呼应
这套做完，可以这样表述（真实、可追问）：
> 基于 Micrometer + Actuator 暴露 Prometheus 指标，对 AI 调用做**低基数指标**（调用次数/耗时直方图/Token 消耗/失败分类）+ **高基数明细落库**（用户、应用、内容），用 Grafana 出趋势、P99、Token 与用户/应用排行。设计上刻意规避了把 userId/内容当标签导致的时序爆炸。


## 九、风险与注意

1. **基数控制**（头号风险）：任何"每用户/每应用/每段文本"都不能当 Prometheus 标签；新增标签前先问"取值会不会无界"。
2. **埋点别阻塞主链路**：明细落库走异步（复用现有线程池/虚拟线程），失败只记日志不影响生成。
3. **推理模型的 token 语义**：`deepseek-v4-flash` 的 reasoning 计入 completion，`ai_tokens_total{kind="output"}` 会包含思考 token——面板说明里注明，避免误读。
4. **隐私**：`assistantContent`/日志含用户内容，注意脱敏与保留期。
5. **部署环境**：actuator 端点建议只在内网暴露（或加鉴权/独立端口），避免公开 `/actuator/prometheus`。
6. **多实例**：本机单实例无所谓；将来多实例要保证 `histogram` 聚合方式与 instance 标签正确（已用 Histogram，OK）。
