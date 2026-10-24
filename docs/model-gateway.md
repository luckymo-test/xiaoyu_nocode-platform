# ModelGateway 改造记录

> 日期：2026-10-24  
> 状态：Phase C 已完成
> 默认：ModelGateway 为唯一模型执行入口；任务路由和 fallback 仍由配置灰度控制

## 一、最终边界

平台业务只描述模型任务和平台语义：

- 任务：生成、修复、修改、视觉选片、查询规划、压缩、研究；
- 输入：messages、tools、温度、输出上限、reasoning 偏好、能力要求；
- 输出：文本、reasoning、tool calls、usage、finish reason、实际 provider/model。

OpenAI/DeepSeek 的 `OpenAiChatOptions`、thinking extra body 和工具执行参数只存在于
`OpenAiCompatibleModelAdapter`。Agent、记忆、素材和研究业务不再直接依赖聊天供应商协议，
也不会直接拼接 `/chat/completions`。

## 二、已完成能力

### 模型目录与路由

- `ModelTask` 覆盖 `GENERATE / REPAIR / MODIFY / VISION_CURATE / QUERY_PLAN /
  COMPRESS / RESEARCH`；
- `ModelDescriptor` 描述 provider、模型名、上下文窗口、最大输出和能力；
- `ModelRegistry` 在调用前校验 tools、vision、streaming、structured-output、
  reasoning 与上下文预算；
- 任务路由开启时按任务选择模型，不在生成循环中把推理档位的旧模型名当成强制覆盖；
- 路由关闭时使用 `default-model`，但仍执行能力与上下文预检。

### 统一执行与韧性

- `AgentToolLoop` 的框架工具和自管工具两种模式都通过 `ModelGateway`；
- 压缩、查询规划和视觉选片全部通过统一 Gateway；
- Web Research 使用独立 `WebResearchTransport`，供应商 endpoint 和密钥不进入业务层；
- 每个模型有独立熔断和并发配额；
- fallback 在流式输出开始前生效，禁止把两个供应商的半截输出拼在一起；
- fallback 对任务路由、显式首选模型和默认模型三条路径都生效；
- 熔断和并发状态当前在单个 Generation Worker JVM 内生效；多 Worker 共享状态仍需
  迁移到 Redis。

### 调用审计与 Revision 快照

- `AiCallContext` 携带 `appId / userId / callType / jobId`；
- `ai_call_log` V14 增加 `jobId` 和 `(jobId, createTime)` 索引；
- `AiCallListener` 统一记录实际 provider、模型 id/name、任务、reasoning level、
  token、耗时、finish reason 和状态；
- Prometheus 只使用低基数标签，不记录 Prompt 正文、模型原始响应或用户敏感内容；
- `ModelExecutionTrace` 在进程内保留同一 Job 的调用副本，避免 Revision 发布与异步落库竞态；
- Revision Manifest 写入：
  - 路由配置、默认模型和 fallback 配置指纹；
  - 每个任务的推理档位；
  - 工具 Schema 单项指纹和组合版本；
  - 生成、Vue 修复、修改、渲染修复 Prompt 的内容指纹；
  - 本 Job 的实际模型调用快照。
- Revision 发布完成或 Job 终止后清理进程内快照，避免长期驻留。

## 三、配置

```yaml
codegen:
  model:
    routing:
      enabled: false
    fallback:
      enabled: false
    resilience:
      enabled: true
      failure-threshold: 3
      open-duration-ms: 30000
      max-concurrent-calls: 8
    default-model: deepseek-flash
    definitions:
      deepseek-flash:
        provider: deepseek
        model-name: ${CODEGEN_DEEPSEEK_MODEL:deepseek-flash}
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
    fallbacks:
      deepseek-flash: []
```

环境变量：

```text
CODEGEN_MODEL_ROUTING_ENABLED
CODEGEN_MODEL_FALLBACK_ENABLED
CODEGEN_MODEL_RESILIENCE_ENABLED
CODEGEN_MODEL_FAILURE_THRESHOLD
CODEGEN_MODEL_OPEN_DURATION_MS
CODEGEN_MODEL_MAX_CONCURRENT_CALLS
CODEGEN_DEEPSEEK_MODEL
```

推荐灰度顺序：

```text
routing=false，验证默认模型与能力预检
→ routing=true，验证任务路由
→ fallback=true，注入单供应商故障并验证切换
```

## 四、验证结果

自动化验证：

- 138 个默认后端测试通过，20 个真实外部探针按约定跳过；
- 视觉任务不会路由到不支持 vision 的模型；
- 工具任务不会路由到不支持 tools 的模型；
- 上下文不足时在调用前失败；
- fallback 只追加满足能力要求的候选；
- 未开任务路由时，默认模型仍可 fallback；
- 业务代码除 Adapter 外不再 import `OpenAiChatOptions`；
- 压缩、查询规划、视觉选片和生成循环都验证写入统一调用审计；
- Revision Manifest 验证包含 Prompt 指纹、Profile、工具版本、路由版本和实际调用。

真实模型与基础设施探针：

- MySQL 5.7 与 8.4 均从空库执行 V1～V15，并运行 Job、ChatHistory 和
  `ai_call_log.jobId` 持久化探针；
- 上下文压缩生成合法结构化摘要；
- 六主题查询规划生成自然、城市、人文、动物、cosplay、电影六个独立 query；
- 视觉选片真实处理 80 个候选，两轮筛选后由模型自主选中 53 张；
- 真实生成在 Gateway + 自管工具循环下执行
  `writeFile / writeFile / readFile / finish`，prompt 75343、completion 24740，
  三档视口渲染验证完成。

## 五、Phase C 后的债务

以下不属于 Phase C 未完成项：

- 熔断和并发配额尚未跨 JVM 共享；Generation Worker 已拆分，但多 Worker 共享状态
  仍需迁移到 Redis；
- `ModelExecutionTrace` 是当前嵌入式 Runner 的进程内加速副本，数据库 `ai_call_log`
  仍是最终明细来源；
- 原生 Anthropic/Gemini 协议只保留 Adapter 扩展点，当前生产实现仍是
  DeepSeek/OpenAI-compatible；
- 真实模型成本与质量基线由 Phase G 评估集持续量化。
