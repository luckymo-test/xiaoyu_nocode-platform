# ModelGateway 改造记录

> 日期：2026-10-24  
> 状态：Phase C 进行中  
> 默认：Gateway、任务路由和 fallback 均关闭，现有调用路径不变

## 一、已完成

### 模型能力目录

新增：

- `ModelTask`：生成、修复、修改、视觉选片、查询规划、压缩、研究；
- `ModelCapability`：tools、vision、streaming、structured-output、reasoning；
- `ModelDescriptor` / `ModelProperties` / `ModelRegistry`；
- 上下文窗口和最大输出预检；
- 按任务路由、模型禁用、能力不匹配和 fallback 候选筛选。

### 统一网关

新增 `ModelGateway`：

```java
ModelResponse chat(ModelRequest request);
Flux<ModelChunk> stream(ModelRequest request);
```

平台请求只描述 task、messages、tools、temperature、输出上限、reasoning 偏好和能力要求。
实际 provider/model 由路由器决定，并在统一响应中返回。

`OpenAiCompatibleModelAdapter` 负责当前 DeepSeek/OpenAI-compatible 协议细节：

- `OpenAiChatOptions` 和 DeepSeek `thinking` extra body 不再进入网关调用方；
- tools、reasoning effort、token 上限和内部工具执行开关只在 Adapter 内组装；
- 文本、reasoning、tool calls、finish reason、usage 统一映射。

### 已迁移调用

- 上下文压缩：`ContextCompressionService` 在 Gateway 开启时通过 `COMPRESS` 任务调用；
- 图库查询规划：`ImageMaterialPlanner` 在 Gateway 开启时通过 `QUERY_PLAN` 任务调用；
- `AgentToolLoop`：框架内置工具和自管工具两种模式都可通过 Gateway 调用；
- 生成循环的 token 预算、取消检查、工具顺序、reasoning 聚合和调用打点仍由
  `AgentToolLoop` 维护，Gateway 只负责模型路由和协议转换；
- 以上调用都保留旧 ChatModel 分支用于灰度回滚。

### 实际模型审计

- `ai_call_log` 新增 `provider` 和 `modelTask`，现有 `model` 改存实际模型名；
- Prometheus 指标新增低基数 `provider`、`model`、`model_task` 标签；
- `AgentToolLoop` 成功调用记录 Gateway 返回的实际 provider/model；
- 失败调用记录任务类型和尝试使用的模型，供应商未知时明确记录 `unknown`；
- Flyway `V13__ai_call_model_audit.sql` 已在 MySQL 5.7 和 8.4 实测通过。

## 二、配置

```yaml
codegen:
  model:
    gateway:
      enabled: false
    routing:
      enabled: false
    fallback:
      enabled: false
    default-model: deepseek-flash
    definitions:
      deepseek-flash:
        provider: deepseek
        model-name: deepseek-flash
        context-window: 128000
        max-output-tokens: 65536
        supports: [tools, vision, streaming, structured-output, reasoning]
        reasoning-style: deepseek-thinking
        enabled: true
    routes:
      generate: deepseek-flash
      repair: deepseek-flash
      modify: deepseek-flash
      vision-curate: deepseek-flash
      query-plan: deepseek-flash
      compress: deepseek-flash
      research: deepseek-flash
```

环境变量：

```text
CODEGEN_MODEL_GATEWAY_ENABLED
CODEGEN_MODEL_ROUTING_ENABLED
CODEGEN_MODEL_FALLBACK_ENABLED
CODEGEN_DEEPSEEK_MODEL
```

推荐顺序：

```text
gateway=false
→ gateway=true, routing=false，验证默认模型路径
→ routing=true，验证任务路由和能力预检
→ fallback=true，验证单一供应商故障
```

## 三、验证

自动化测试覆盖：

- YAML 风格配置绑定；
- 视觉任务只能路由到具备 vision 的模型；
- 上下文窗口不足时调用前失败；
- fallback 只追加满足能力的候选；
- DeepSeek/OpenAI-compatible 参数在 Adapter 内组装；
- 统一响应映射文本、reasoning、finish reason 和 usage；
- 压缩、查询规划和 AgentToolLoop 在开关开启时不再直接调用旧 ChatModel。
- 调用明细与 Prometheus 使用实际 provider/model/task，不再只依赖全局默认模型。

真实模型回归已执行：

- Gateway 模式下上下文压缩生成合法结构化摘要；
- Gateway 模式下六主题查询规划分别生成自然、城市、人文、动物、cosplay、电影 query；
- Gateway + 自管工具循环完成真实 HTML/CSS 生成，实际调用 `writeFile/readFile/finish`，
  总 token 为 prompt 209724 / completion 51727，三档视口渲染验证通过。

## 四、尚未完成

以下仍直接依赖旧路径，因此 Phase C 未完成：

- `ImageCuratorAgent` 仍硬编码视觉模型并直接 POST `/chat/completions`；
- `WebResearchClient` 仍直接使用 DeepSeek Search/Fetch endpoint；
- 尚无模型级熔断、限流和真实第二供应商契约测试；
- 尚无 Generation/Revision 级模型与路由版本快照。

下一小步应迁移视觉选片和调用明细字段，并增加第二供应商契约测试。
