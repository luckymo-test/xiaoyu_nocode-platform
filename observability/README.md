# AI 调用可观测性（Prometheus + Grafana）

对 AI 模型调用做监控：指标上报 Prometheus、Grafana 出趋势/性能/Token 面板，并支持"热门应用/活跃用户"排行。

## 数据分布（重要）

| 数据 | 去处 | 说明 |
|---|---|---|
| 低基数聚合指标 | **Prometheus**（`/actuator/prometheus`） | 只打 model / call_type / status / kind / error_type，避免时序爆炸 |
| 高基数明细 | **MySQL `ai_call_log`** | userId、appId、错误信息、逐次 token/时长；供排行与排查 |
| 原始内容 | MySQL `chat_history`（已有） | "AI 回复消息内容"以对话历史为准 |

> 设计红线：**userId / appId / 消息内容 / 错误原文绝不作为 Prometheus 标签**（会为每个取值建独立时序 → 时序爆炸）。

## 指标（由 `core/AiMetricsRecorder` 打点，`core/AiCallListener` 落明细）

| 指标 | 类型 | 标签 |
|---|---|---|
| `ai_call_total` | Counter | model, call_type, status |
| `ai_call_duration_seconds` | Histogram | model, call_type（含自定义 SLO 桶，可算 P50/P90/P95/P99） |
| `ai_tokens_total` | Counter | model, call_type, kind=input/output/total |
| `ai_call_errors_total` | Counter | model, call_type, error_type(network/truncation/rate_limit/auth/other) |

## 启动

前置：本机装了 Docker；后端在宿主机运行（业务端口 8124、**指标端口 8125**）。

```bash
# 建表：已由 Flyway 迁移接管（src/main/resources/db/migration/V1__init.sql，随后端启动自动执行）
cd observability
MYSQL_PASSWORD=你的库密码 docker compose up -d
```

- Prometheus：http://localhost:9090 （抓取目标 `host.docker.internal:8125/actuator/prometheus`）
- Grafana：http://localhost:3000 （admin / `GRAFANA_ADMIN_PASSWORD`，默认 admin）
- 仪表盘 "AI 调用可观测性" 会自动加载（provisioning）

## 验证指标是否上报

```bash
curl -s http://localhost:8125/actuator/prometheus | grep '^ai_'
```
跑一次生成后应能看到 `ai_call_total{status="success"}`、`ai_tokens_total` 增长。

## 面板

| 面板 | 数据源 | 说明 |
|---|---|---|
| 模型调用趋势 | Prometheus | `increase(ai_call_total) by(call_type)` |
| 调用成功率 / 总次数 | Prometheus | 近 1h |
| 响应时长 P50/P90/P95/P99 | Prometheus | `histogram_quantile(...)` |
| Token 消耗（输入/输出/总） | Prometheus | `ai_tokens_total by(kind)` |
| 失败原因分布 | Prometheus | `ai_call_errors_total by(error_type)` |
| 热门应用排行 | MySQL | `ai_call_log GROUP BY appId` |
| 用户活跃排行 | MySQL | `ai_call_log GROUP BY userId` |

## 本机原生安装（Windows，当前实际使用方式）

若 Prometheus/Grafana 不是 Docker 而是本机安装（本项目实际就是），按下面配置：

**Prometheus**（zip 版，如 `D:\computer tools\Prometheus\prometheus-3.14.0.windows-amd64\...`）
1. 编辑安装目录下的 `prometheus.yml`，加一个抓取任务（见 [prometheus/prometheus.yml](prometheus/prometheus.yml)，把 target 换成 `localhost:8125`）；
2. 重启 `prometheus.exe`（改配置需重启或用 `--web.enable-lifecycle` + `POST /-/reload`）；
3. 校验：`curl "http://localhost:9090/api/v1/targets"` 中 `job=xiaoyu-zerocode` 的 `health=up`；`/api/v1/query?query=ai_call_total` 有数据。

**Grafana**（解压/MSI 版，如 `D:\computer tools\Grafana\grafana`）
- 用 API 配置（无需重启；账号默认 admin/admin）：
```bash
# 1) 建 MySQL 数据源（密码必须放 secureJsonData）
curl -u admin:admin -X POST http://localhost:3000/api/datasources \
  -H "Content-Type: application/json" -d '{
    "name":"MySQL","type":"mysql","uid":"mysql","access":"proxy",
    "url":"localhost:3306","database":"xiaoyu_zerocode","user":"root",
    "jsonData":{"maxOpenConns":5},"secureJsonData":{"password":"你的库密码"}}'
# 2) 导入仪表盘（把 json 里的 datasource.uid 换成实际 uid 再导入）
curl -u admin:admin -X POST http://localhost:3000/api/dashboards/db \
  -H "Content-Type: application/json" \
  -d '{"dashboard": <ai-observability.json 内容>, "overwrite": true, "folderId": 0}'
```
- 或放到 Grafana 的 `conf/provisioning/`（datasources + dashboards）后重启 Grafana。
- 本机原生安装时数据源地址用 `localhost`；Docker 场景才用 `host.docker.internal`。

> 说明：本仓库 [grafana/](grafana) 下的 provisioning 文件是 Docker 版（地址写 host.docker.internal）；本机原生安装用上面的 API/或改成 localhost 即可。

## 备注

- 指标端点为**独立端口 8125**（`management.server.port`），不随业务端口暴露；生产建议再加内网/鉴权限制。
- 一次生成（含框架内部多轮模型调用）记为**一条明细 / 一次逻辑调用**，token 为该次生成累计值。
- `deepseek-v4-flash` 的 reasoning 计入 output token，Token 面板已注明。
- MySQL 数据源密码通过 compose 的 `MYSQL_PASSWORD` 环境变量注入，不写死在仓库。
