# 计划书：Agent 解耦与专用 Agent 建设（AgentToolLoop / SiteRepairAgent / ImageCuratorAgent）

> 日期：2026-10-18
> 前提：`VueProjectAgent` 已 839 行，同时承担"生成、构建修复、渲染修复、可视化修改、工具循环引擎、
> 上下文压缩、结果组装"七类职责；继续加功能会重新变成 god class。
> 目标：**A. 工程解耦**——把共享引擎与修复流程拆成独立类（不改变现有行为）；
> **B. 专用 Agent**——新增多模态 `ImageCuratorAgent` 负责图片筛选，把"选片"从程序规则升级为视觉判断。

---

## 零、优先级总览

| 优先级 | 事项 | 依赖 | 风险 | 为什么放这个位置 |
|---|---|---|---|---|
| **P0（配置修正）** | 模型接入修正：`/beta` → 正式域名、`deepseek-v4-flash` → `deepseek-flash`、复核 `max-tokens` | 无 | 低 | 改动极小但影响所有链路；先纠正"历史遗留配置" |
| **P0** | 抽 `AgentToolLoop`（纯搬运，行为不变） | 无 | 低 | 地基：修复类必须复用它，先抽可避免反向依赖 |
| **P1（依赖 P0）** | 推理强度滑杆：thinking off / low / high / max | P0（配置修正 + AgentToolLoop） | 中 | 用户可见能力；options 只能在循环引擎里统一注入 |
| **P1** | 抽 `SiteRepairAgent`（构建/渲染修复） | P0 | 低（行为等价） | 把修复链路独立出来，为后续迭代与测试铺路 |
| **P1（可插队）** | 新增 `ImageCuratorAgent`（多模态选片） | 无（不依赖 P0/P1） | 中（新能力、模型非确定性） | 用户价值最高；**与解耦互不影响，可随时插到 P0 之后** |
| **P2** | 抽 `SiteModifyAgent`（可视化修改） | P0 | 中 | 与 P1 手动编辑耦合，等修复类稳定后再做 |
| **P3（不在本计划）** | 包结构归拢（`ai/` → `generation/agent/`） | 全部 | 中 | 纯搬迁、改动面最广，留给 8 号任务 |

**推荐执行顺序**：P0（配置修正）→ P0（AgentToolLoop）→ P1（ImageCuratorAgent / SiteRepairAgent / 推理滑杆，三者按需插队）→ P2 → P3。

- 想先看到**产品效果**：配置修正 → AgentToolLoop → ImageCuratorAgent。
- 想先把**代码结构**理顺：配置修正 → AgentToolLoop → SiteRepairAgent。
- 三个 P1 之间没有硬性依赖（推理滑杆依赖 AgentToolLoop），顺序可互换。
- **AgentToolLoop 不能跳过**（否则修复类会反向依赖生成类）。

---

## 一、现状盘点（改前基线）

`src/main/java/com/mofanyi/ai/VueProjectAgent.java`：**839 行**。

| 方法 | 起始行 | 职责 | 归属 |
|---|---|---|---|
| `generate` / `buildMemorySummary` | 192 / 255 | 生成入口、素材清单、记忆、结果 | 保留在生成类 |
| `fixBuildError` | 275 | Vue 构建失败修复 | **SiteRepairAgent** |
| `fixRenderIssues` | 304 | 渲染验证问题修复 | **SiteRepairAgent** |
| `modifyGeneratedSite` / `restoreFiles` | 343 / 376 | 可视化修改 / 撤销 | 暂留（可后续独立） |
| `runToolLoop` | 394 | 框架/自管循环入口 | **AgentToolLoop** |
| `runToolLoopSelfManaged` | 505 | 自管 Tool-Calling 循环 | **AgentToolLoop** |
| `streamAndAggregate` | 744 | 流式聚合（文本/toolCalls/reasoning/usage） | **AgentToolLoop** |
| `checkBudget` / 打点 / L1 压缩 | 484~727 | 预算、取消、逐轮打点、上下文压缩 | **AgentToolLoop** |
| `assembleResult` / `buildFileIndex` / `buildFilesContext` | 811~846 | 结果与文件上下文 | 生成类 / ProjectCodeScanner |

**调用点**（已核）：
- `AiCodeGeneratorFacade`：`generate` / `streamGenerate` / `fixRenderIssues` / `streamModifyProject`
- `VueBuildService`：`fixBuildError`

**基线验证**：`mvn test` 38 个用例（1 个探针按设计跳过）；另跑一次真实生成，记录轮数、token、`[verify]` 结果作为对照。

---

## 二、目标结构

```
ai/
  VueProjectAgent      // 只负责生成：素材清单 + 记忆 + 结果组装（目标 < 500 行）
  SiteRepairAgent      // 构建修复 + 渲染修复（本计划核心）
  ImageCuratorAgent    // 多模态选片：候选图 → 打分/分用途/写描述（单次评估，不是工具循环）
  AgentToolLoop        // 共享引擎：两种循环 + 流式聚合 + 预算/取消/打点/L1
  tools/               // 现有 5 个工具，不动
  memory/              // 现有记忆与规格摘要，不动
```

`AgentToolLoop` 提供唯一入口：

```java
void run(ToolLoopRequest request);

record ToolLoopRequest(
    CodeGenTypeEnum type, Long appId,
    List<Message> messages,                 // 可变：循环会追加历史
    Map<String, String> files,              // writeFile 收集
    AtomicBoolean finished, AtomicReference<String> description,
    TokenUsageAccumulator usage,            // 修复流程当前传 null（保持现状）
    AiCallContext ctx, VueProgressListener listener,
    List<ImageMaterialPlanner.Material> materials);
```

生成类与修复类都只负责"准备 messages → 调 `agentToolLoop.run(...)` → 处理结果"。

---

## 三、方案选择：抽组件，不复制整个类

**推荐（方案 A）**：新文件写 `AgentToolLoop` / `SiteRepairAgent`，旧文件只做"删掉被搬走的方法 + 改为委托"。

- 优点：不会出现两份同构实现；旧文件确实会变小；每一步可独立提交与验证。
- 代价：旧文件会被改动（但只是删代码与改调用，不是继续堆逻辑）。

**备选（方案 B，若坚持"旧文件零改动"）**：整类复制为 `CodeGenAgent` + 新组件，旧类标 `@Deprecated`。

- 硬性收尾条件（缺一不可）：
  1. 装配一次性切换（Facade / VueBuildService 全部改注入新类）；
  2. 旧类**冻结**，期间任何 bug 只修新类；
  3. 日志明确当前实现（如 `[agent] impl=CodeGenAgent`）；
  4. **下一个提交删除旧类**（`rg` 零引用 + 编译 + 一次真实生成）。
- 若窗口超过 2 个提交，视为技术债，应回退到方案 A。

> 本计划按**方案 A** 编写；方案 B 只作为安全备选记录。

---

## 四、分步执行计划

### Step 0：基线（不改代码）

- 记录 `VueProjectAgent` 行数、`mvn test` 结果、一次真实生成的日志（轮数 / `[素材]` / `[scan]` / `[verify]`）。
- 保存为对照，后续每步与它比。

**验收**：基线数据落档（`plan-agent-decoupling.md` 或 `tmp/` 日志）。

### Step 1：抽 `AgentToolLoop`（纯搬运，行为不变）

**新文件**：`ai/AgentToolLoop.java`

**搬走**：`runToolLoop`、`runToolLoopSelfManaged`、`streamAndAggregate`、`checkBudget`、`logRoundUsage`、
`slimHistory`、`slimToolResponses`、`compactWriteFileArguments`、`finishReasonOf`，
以及相关常量与开关（`MAX_STEPS` / `MAX_LOOP_MILLIS` / `max-loop-tokens` /
`self-managed-loop.enabled` / `context-slim.enabled`）。

**注入**：`ChatModel`、5 个工具 Bean、`GenerationTaskRegistry`、`AiCallListener`。

**旧类改动**：删除上述方法，`generate` / `fix*` / `modify` 改为调用 `agentToolLoop.run(...)`。

**验证门槛**：
1. `mvn -o test` 38 用例全绿；
2. 一次真实生成（html）：素材 → 各轮 → `[scan]` → `[verify]` 与基线同形；
3. 开关回归：`self-managed-loop=true/false` 都能跑（日志 `模式=self/framework`）；
4. 取消回归：生成中点停止 → `GenerationCancelledException` + 状态 `stopped`。

**回滚**：单提交可整体 revert。

### Step 2：抽 `SiteRepairAgent`

**新文件**：`ai/SiteRepairAgent.java`

**搬走**：`fixBuildError`、`fixRenderIssues`（含消息构造、manual-edit 快照/重放、`recordChange`）。

**注入**：`AgentToolLoop`、`CodeGenPromptRegistry`（`vueFix()` / `renderFix()`）、`ManualEditService`、
`AppSpecService`、`ProjectCodeScanner`、`CodeFileWriteService`。

**调用点改造**：
- `AiCodeGeneratorFacade.fixRenderIssues` → `siteRepairAgent.fixRenderIssues(...)`
- `VueBuildService.fixBuildError` → `siteRepairAgent.fixBuildError(...)`

**行为保持（重点核对）**：
- prompt 与消息顺序不变；
- `snapshot` → `try` → `run` → `finally replay` 的时机不变；
- `recordChange` 的 kind / summary 不变；
- 异步修复的 appId 锁重试仍留在 Facade（不搬进 Agent）。

**验证门槛**：
1. 渲染修复：确定性触发（引用不存在的本地图片路径）→ `3/3 档检出` → 修复 → `复验 3 档通过`；
2. 构建修复：先生成 Vue 工程，再故意制造一个构建错误（如破坏一个 `.vue` 文件的语法），
   走预览/构建触发 `fixBuildError` → 修复后构建通过；
3. `mvn -o test` 全绿。

**回滚**：单提交 revert。

### Step 3（可选）：抽 `SiteModifyAgent`

**搬走**：`modifyGeneratedSite`、`restoreFiles`。调用点：`AiCodeGeneratorFacade.streamModifyProject`。

**验证**：SSE 可视化修改回归（`agent_progress` + `reasoning` + `done`）；手动编辑快照/重放在修改后仍生效。

**建议**：等修复类稳定后再做；它和 P1 手动编辑逻辑耦合，单独一步验证更清楚。

### Step 4：收尾

- `rg` 确认循环/修复逻辑只有一份实现；
- 更新 `plan-refactor.md` 7.1 的 S5/拆分状态与类结构说明；
- 记录最终行数（目标：`VueProjectAgent < 500`）。

---

## 五、ImageCuratorAgent（多模态选片，P1 / 可插队）

### 5.1 定位

一个**单次判断的多模态评估器**：输入"用户需求 + 候选图"，输出"选哪些、每张干什么用、每张是什么"。
它**不是工具循环**——不需要 readFile/writeFile，不需要多轮，一次调用给结构化结论即可。

### 5.2 在流水线中的位置

```
用户需求
  → 多 query 检索（现有 ImageSource，pexels → pixabay）
  → 候选池（当前 24~48 张）
  → 【硬闸门：可下载 / 可解码 / 真实尺寸】          ← 保留在程序侧（技术前置条件）
  → 【ImageCuratorAgent：看缩小图 → 打分 / 分用途 / 描述 / alt】  ← 新增
  → 素材清单（URL + 尺寸 + 用途 + 描述 + alt）
  → 生成 Agent 照清单写页面
```

**职责边界**：

| 环节 | 归谁 | 理由 |
|---|---|---|
| 能否下载/解码、真实宽高 | 程序 | Agent 看不到坏图，技术有效性必须先保证 |
| 语义相关、风格一致、构图质量、视觉重复、水印 | **Agent** | 这些需要"看图"判断 |
| 用途分配（hero/portrait/card/icon…） | **Agent** | 结合内容与比例给出建议 |
| 最终取前 N、用途配额校验 | 程序 | 用 Agent 的分数做确定性收口，保证可复现 |

> 用户口径是"所有筛选交给 Agent"：语义/质量/用途这类**筛选**全部交给它；
> 程序只保留"能不能用"的技术闸门和"按分数取前 N"的记账。这是为了保证结果可复现、可审计。

### 5.3 输入契约

- 用户需求原文
- 候选列表：`id`（0..N，仅本次请求有效）+ 真实宽高 + 来源检索词
- **图片本体：下载 → 缩放 → base64 data URL（主路径）**：
  - 长边缩到 **768~1024px**、JPEG 质量 ~80；单张约 100~300KB，8~12 张合计远小于 48MiB 请求体上限；
  - 必须自己缩放的原因：官方限制**单边最长 8192px**，而我们的候选原图可能到 8256px（会直接被拒）；
    且「单请求 ≥15 张时上限降为 4096px」，自己缩放后两个限制都不会踩到；
  - `detail: "low"`（推理前缩到 512×512）用于第一轮筛选：更快、更省 token，且 ≤1024 token/张；
  - 外部 URL 仅作为备选：要求 URL ≤8192 字符、图片 ≤32MiB、单边 ≤8192（≥15 张时 ≤4096），且 COS 必须公网可读；
  - **图片只能放在 `user` 消息里**；放进 system/assistant 消息会直接 400。
- 按 **8~12 张/批**发送（官方单请求上限 600 张，但批量小更稳、更易解析）。

### 5.4 输出契约（严格 JSON）

```json
{
  "needed": true,
  "suggestedCount": 6,
  "selected": [
    {"id": 3, "score": 9, "purpose": "hero",
     "description": "穿汉服的女性站在园林中，暖色调",
     "alt": "汉服写真主视觉"},
    {"id": 11, "score": 8, "purpose": "portrait",
     "description": "侧脸人像特写，背景虚化",
     "alt": "汉服人像特写"}
  ],
  "rejected": [
    {"id": 7, "reason": "与需求无关"},
    {"id": 9, "reason": "与 3 号视觉重复"}
  ]
}
```

约束：
- `purpose` 用受控枚举：`hero / background / gallery / portrait / card / icon / avatar`；
- 只能引用候选 `id`，禁止输出 URL；
- 只输出 JSON（无解释文字）；解析失败重试一次，再失败**回退到现有程序清单**。

### 5.5 提示词（专用文件）

`src/main/resources/prompt/image_curator_prompt.txt`，要点：

1. 角色：网站视觉项目的**选片导演**；
2. 评判维度：语义相关 / 风格统一 / 构图可用（主体清晰、留白、横竖适配）/ 避免视觉重复；
3. 硬约束：只能选候选编号、同一张不重复、按 schema 输出；
4. **两轮提示词**：
   - Pass 1（all candidates，`detail=low`）：语义相关、风格、构图、用途分类；
   - Pass 2（shortlist only，`detail=high/original`）：复核水印、模糊、细节质量，并生成最终 `description/alt`；
     （`low` 会把图缩到 512×512，水印/细微瑕疵可能看不清，所以精细判断放第二轮。）
5. Curator 默认**关闭思考**（`thinking: disabled`）以降低延迟；此时 `temperature` 才真正生效（0.2）。
   若实测选片质量不足，再 A/B 打开 thinking high。

### 5.6 代码落点

| 文件 | 改动 |
|---|---|
| `ai/ImageCuratorAgent`（新） | 下载/缩放（长边 768~1024）/base64/分批多模态调用/JSON 解析/重试/降级 |
| `src/main/resources/prompt/image_curator_prompt.txt`（新） | 专用提示词 |
| `core/ImageMaterialPlanner` | 候选池后调用 Curator；`Material` 增加 `purpose / description / alt` |
| `core/ImageMaterialPlanner.toPromptSection` | 清单从"URL+尺寸"升级为"URL+尺寸+用途+描述+alt" |
| `core/ImageMaterialPlanner` 现有闸门 | 去重/分辨率保留为技术前置；比例分桶限量**交给 Agent**，程序只做配额校验 |

Curator 的模型必须**固定为 `deepseek-flash`**：价格页明确 `deepseek-v4-pro` **不支持图像理解**，
不能跟随"生成模型路由"切到 pro。Curator 调用参数：

```java
OpenAiChatOptions.builder()
    .model("deepseek-flash")
    .temperature(0.2)
    .extraBody(Map.of("thinking", Map.of("type", "disabled")))  // 单次视觉判断，不需要长思考
    .build();
```

### 5.7 开关与降级

- 开关：`codegen.materials.curator.enabled`（**默认 false**，验收通过后改 true）；
- 失败降级链：视觉调用失败/超时/JSON 非法 → 重试一次 → 仍失败则用现有"URL+尺寸"清单，**不阻塞生成**；
- 已知会直接 400 的场景（降级日志要记录原因）：
  图片出现在 `system`/`assistant` 消息；单边 >8192px（≥15 张时 >4096px）；URL >8192 字符；
  单图 >32MiB；请求体 >48MiB；格式不是 JPEG/PNG/GIF/WebP。
- 能力回归：`ImageInputProbeTest`（默认跳过）保留，用于验证"当前模型/网关仍支持图片输入"。

### 5.8 产物与可观测

- 落盘 `.agent/materials.json`：候选数、选中/拒绝、理由、每张的用途与描述；
- 日志：`[curator] 候选=32 选中=6 拒绝=26 耗时=…ms`，便于对比调优；
- 生成侧仍记录 `[素材]` 清单，便于核对"页面用图 ⊆ 选中集合"。

### 5.9 验收

1. 图片主导 prompt 真实生成一次：`.agent/materials.json` 存在且 schema 合法；
2. 页面中的图片 URL **全部**在 `selected` 集合内；
3. `alt` 与 `description` 一致、不再出现"图里没有的东西"这类明显幻觉；
4. 渲染验证 3 档通过；
5. 关闭开关时行为回到当前版本（可回滚）；
6. 记录耗时/token 增量（用户当前明确"暂不考虑成本"，但数据要留）；
7. 请求结构校验：图片只出现在 `user` 消息；单图长边 ≤8192（≥15 张时 ≤4096）；
8. token 口径：单图 ≤1024 token（官方上限），8 张约 ≤8K 图像 token，实际记录每批用量。

### 5.10 风险

| 风险 | 对策 |
|---|---|
| 选片结果不稳定 | 低温度 + 程序按分数取前 N + 用途配额 |
| 模型描述幻觉 | 描述只作辅助；布局仍以真实尺寸为准；schema 校验 |
| 单次图片过多被网关拒绝 | 8~12 张/批；失败降级 |
| 换模型/网关后不支持图片 | `ImageInputProbeTest` 回归；不支持则自动降级 |
| 版权 | 只从 Pexels/Pixabay 等授权图源取图，不引入爬站 |
| 原图超过 8192px（实测候选有 8256px） | **自己下载并缩放**，不发原始 COS URL |
| 误路由到 `deepseek-v4-pro`（不支持图像） | Curator 模型固定 `deepseek-flash` |
| `detail=low` 看不清水印/瑕疵 | Pass 1 低清筛选，Pass 2 对 shortlist 用 high/original 复核 |
| ≥15 张时尺寸上限降到 4096 | 批量 ≤14，或全部缩到长边 ≤4096 |

### 5.11 官方视觉能力约束（2026-10 核对，必须遵守）

| 限制项 | 数值 / 行为 |
|---|---|
| 支持模型 | `deepseek-flash`（`deepseek-v4-pro` **不支持**图像） |
| 输入方式 | base64 data URL / 外部 URL / Files API（file_id） |
| 支持格式 | JPEG、PNG、GIF、WebP（按文件内容判断，不认文件名/MIME 声明） |
| 外部 URL 长度 | ≤ 8192 字符 |
| 请求体大小 | ≤ 48 MiB |
| 单张图片（base64 / URL） | ≤ 32 MiB |
| 单张图片（Files API） | ≤ 64 MiB |
| 单请求最大图片数 | 600 |
| 单请求图片总大小 | 不含 file_id ≤64 MiB；含 file_id ≤200 MiB |
| 图片最大尺寸 | 单边 ≤8192px；**单请求 ≥15 张时降为 ≤4096px** |
| detail | `low`=缩放 512×512；`high/original/auto`=保留原图 |
| token | 每张最多 **1024 token**；小图会放大到约 544×544，大图缩到约 1300×1300 |
| 图片位置 | **只能放 `user` 消息**；system/assistant 带图 → 400 |

---

## 六、推理强度与模型接入（P1）

### 6.1 官方事实（2026-10 核对 thinking_mode + pricing 文档）

- **思考模式默认开启，effort 默认 `high`**。
- 有效档位只有三档：`low / high / max`；请求别名映射如下：

| 请求 effort | 实际 effort |
|---|---|
| minimal / low | low |
| medium / high / xhigh | **high** |
| max / ultra | max |

- 思考模式可显式关闭：OpenAI 格式 `extra_body: {"thinking":{"type":"disabled"}}`。
- **思考模式下 `temperature` / `presence_penalty` / `frequency_penalty` 不生效**（设置不报错但被忽略）；
  `top_p` 也有模型相关约束（flash 思考模式仅 0.95–1.0，非思考模式恒 1.0）。
- **带 `tools` 的请求，历史轮次的 `reasoning_content` 必须回传**，否则 API 返回 400；不带 tools 时可回传但会被忽略。
- 模型与价格（百万 tokens，空闲/高峰）：

| | deepseek-flash | deepseek-v4-pro |
|---|---|---|
| 上下文 / 最大输出 | 1M / 384K | 1M / 384K |
| 图像理解 | ✅ | ❌ |
| 输入·缓存命中 | 0.02 / 0.04 元 | 0.15 / 0.30 元 |
| 输入·未命中 | 1 / 2 元 | 4.5 / 9 元 |
| 输出 | 4 / 8 元 | 13.5 / 27 元 |

- 正式域名 `https://api.deepseek.com` 已支持 1M 上下文 / 384K 输出；**Beta 仅用于"对话前缀续写 / FIM"**，本项目的长输出不再需要 `/beta`。
- 旧模型名 `deepseek-v4-flash` 仍可调用，但对应模型已下线，会映射到 `DeepSeek-V4.1-Flash-1017` 并按 Flash 计费。

### 6.2 P0 配置修正（先做，改动极小）

1. `spring.ai.openai.base-url`：`https://api.deepseek.com/beta` → **`https://api.deepseek.com`**
   （理由：#34 的"非 beta 只认 4K/8K"已被 384K 输出上限取代；Beta 特性我们没用到）。
2. `spring.ai.openai.chat.options.model`：`deepseek-v4-flash` → **`deepseek-flash`**（避免依赖旧别名）。
3. 复核 `max-tokens: 32768`：正式接口最大输出 384K，32K 偏保守；可按实测上调到 64K。
   注意 `max_tokens` 是**上限不是目标**，费用按实际输出 token 计。
4. 验收：一次真实长生成不截断；`ai_call_log.model` 记录为 `deepseek-flash`。

### 6.3 滑杆档位设计（4 档，含"关闭思考"）

| UI 档位 | 思考模式 | 实际参数 | maxSteps | maxLoopTokens | 修复轮数 |
|---|---|---|---|---|---|
| **极速** | 关闭 | `extra_body={"thinking":{"type":"disabled"}}` | 8 | 20 万 | 1 |
| **省算力** | 开启 | `reasoning_effort=low` | 12 | 40 万 | 1 |
| **标准（默认）** | 开启 | `reasoning_effort=high` | 20 | 80 万 | 2 |
| **极限** | 开启 | `reasoning_effort=max` | 30 | 120 万 | 3 |

约束：
- **不把 temperature/top_p 放进滑杆**（思考模式下无效，属于假控制）；
- 墙钟上限 **≤ 14 分钟**（`GenerationRecoveryRunner` 15 分钟会回收）；
- 日志/指标记 **effective** 值（`thinking=on/off`、`effort=low/high/max`），不记 `medium/xhigh` 这类别名；
- 默认 `high` = 当前行为，切换滑杆不应改变默认产出的语义。

### 6.4 实现落点

1. `ReasoningLevel` 枚举 + `ReasoningProfile` record（thinkingEnabled / effort / maxSteps / maxLoopTokens / repairRounds / maxOutputTokens / model）。
2. `ReasoningProfileRegistry`：从 `application.yml` 读映射，换模型只改配置。
3. 请求携带 `reasoningLevel`；前端 4 档滑杆 + `localStorage` 记忆（按应用持久化可后续加 `app.reasoningLevel`，走 Flyway V2）。
4. `AgentToolLoop` 每轮构造 options 时使用 **`OpenAiChatOptions`**（本版本已支持 `reasoningEffort` / `extraBody` / `toolCallbacks` /
   `internalToolExecutionEnabled`）：

```java
OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
        .toolCallbacks(toolCallbacks)
        .internalToolExecutionEnabled(false)
        .maxTokens(profile.maxOutputTokens());

if (profile.thinkingEnabled()) {
    b.reasoningEffort(profile.effort());               // low / high / max
} else {
    b.extraBody(Map.of("thinking", Map.of("type", "disabled")));
}
```

5. 指标加低基数标签 `reasoning_level=fast/eco/standard/max`，便于按档位对比耗时与 token。

### 6.5 已知阻塞：`reasoning_content` 回传（必须先验证）

官方要求：**带 tools 时，历史轮次的 `reasoning_content` 必须完整回传，否则 400**。

代码核查结论（Spring AI 1.1.8，`javap` 核对）：
- `OpenAiApi.ChatCompletionMessage` **有** `reasoningContent` 字段；
- 但 `OpenAiChatModel` 把 `AssistantMessage` 转成请求消息时只用了 `getText()` / `getToolCalls()` / `getMedia()`，
  **没有读 metadata 里的 `reasoningContent`**；
- 我们的自管循环 `streamAndAggregate` 手工构造 `AssistantMessage(content + toolCalls)`，**reasoning 被丢弃**。

现状：已有大量自管循环真实生成（最多 20 轮、带 tools）**未出现 400** → 当前网关对这条约束宽松；
但**不能依赖宽松**（换网关/模型可能立刻失败），且模型下一轮看不到自己上一轮的思考，**思考连续性丢失**，
高档 effort 的价值会被打折。

处理选项（需先做探针确认）：

| 方案 | 做法 | 代价 |
|---|---|---|
| A | 工具调用轮次改回框架循环（框架内部保留原始 message），自管只用于非工具轮次 | 失去轮次间预算检查 |
| B | 自管循环绕过 Spring AI 的消息转换，直接构造 OpenAI 请求（带 reasoning_content） | 改动大、完全可控 |
| C | 把 reasoning 拼进 assistant `content` 作为普通文本回传 | 保上下文但污染 content，不推荐长期 |
| D | 升级 Spring AI 到支持 metadata → reasoningContent 映射的版本 | 依赖版本节奏 |

**探针门槛**：forced 2 轮工具调用 + `high`，确认是否 400、以及是否出现"重复读文件/重复写文件"等质量退化；
结果决定选 A/B/C/D 还是暂时只开 low/high、不开 max。

### 6.6 成本与缓存（实现时写进 UI 提示）

- 以 flash 空闲价估算历史实测：自管 `182865 + 31738` ≈ **0.31 元**；框架 `406371 + 57186` ≈ **0.64 元**；
  高峰价翻倍。`max` 档主要增加 completion/reasoning token，单次多几毛到一块多，**延迟**才是主要代价。
- 缓存命中价差 25~50 倍（0.02 vs 1 元）→ 继续保持"静态 system prompt 在前、动态内容在后"的结构；
  滑杆只改 options，不改前缀，缓存仍然有效。

### 6.7 验收

**P0 配置修正**
- [ ] 正式域名 + `deepseek-flash` 启动正常；
- [ ] 一次真实长生成不截断；
- [ ] `ai_call_log` 的记录模型为 `deepseek-flash`。

**P1 滑杆**
- [ ] thinking-off 探针通过，且 tools 仍可调用；
- [ ] low / high / max 各跑一次真实生成：记录 reasoning 字符、总 token、耗时、渲染验证结果；
- [ ] `medium` 不出现在 UI（或标注为 high 的别名）；
- [ ] 默认档与当前行为一致（high）；
- [ ] 指标/日志带 `reasoning_level`；
- [ ] `reasoning_content` 探针结论归档；未解决前不把 max 设为默认。

---

## 七、风险与对策

| 风险 | 对策 |
|---|---|
| 搬运导致行为漂移 | 消息顺序/prompt/快照时机逐项对照；每步跑真实生成 |
| 两份实现长期并存 | 采用方案 A；若用方案 B，删除窗口 ≤ 2 个提交 |
| DI 注入歧义 | 每类只保留一个 Bean；切换调用点一次完成 |
| 异步修复锁被搬错层 | 锁与重试留在 Facade，Agent 不感知 appId 锁 |
| 修复流程 `usage=null` 导致预算不生效 | **本计划保持现状**（行为等价）；作为独立待办单独修 |
| 上下文压缩/自管开关被拆散 | 与循环一起搬进 `AgentToolLoop`，配置 key 不变 |

---

## 八、验收总清单

**A. 解耦（P0/P1/P2）**

- [ ] `VueProjectAgent` < 500 行，且不再包含 `runToolLoop*` / `streamAndAggregate`
- [ ] `AgentToolLoop` 是循环逻辑的唯一实现（`rg "internalToolExecutionEnabled"` 只命中它）
- [ ] `SiteRepairAgent` 是两条修复流程的唯一实现
- [ ] 38 个用例全绿
- [ ] 真实生成（html）与基线同形
- [ ] 渲染修复：坏图 3/3 检出 → 修复 → 复验通过
- [ ] 构建修复：故意制造构建错误 → 修复后构建通过
- [ ] 无 `@Deprecated` 的重复实现残留（若走方案 B）

**B. ImageCuratorAgent（P1）**

- [ ] 专用提示词文件落库，Agent 只输出约定 JSON
- [ ] 候选 `id` 合法、URL 全部来自候选、`purpose` 在枚举内
- [ ] 页面图片 URL ⊆ `selected` 集合，`alt` 与描述一致
- [ ] 开关关闭时行为与当前版本一致；开启时渲染验证 3 档通过
- [ ] 失败/超时/非法 JSON 能降级到现有清单，不阻塞生成
- [ ] `.agent/materials.json` 可追溯选中/拒绝理由

**C. 模型接入与推理滑杆（P0/P1）**

- [ ] `base-url` 已切正式域名、模型名为 `deepseek-flash`，真实长生成不截断
- [ ] thinking off / low / high / max 四档均可运行（若网关不支持 off，则收敛为三档）
- [ ] `medium` / `xhigh` 不作为 UI 档位出现
- [ ] `reasoning_content` 回传探针有结论，且未解决前 `max` 不设为默认
- [ ] 指标/日志记录 effective 档位，成本与耗时可按档位对比

---

## 九、待定问题

1. `modifyGeneratedSite` 是否也独立成 `SiteModifyAgent`（本计划先标为可选）。
2. 修复流程是否借此机会修掉 `usage=null`（会让预算对修复生效，但属于行为变更，建议单独一步）。
3. 包结构归拢（`ai/` → `generation/agent/`）是否并入本计划（建议放到 8 号任务，不混在一起）。
4. `ImageCuratorAgent` 与 `SiteRepairAgent` 谁先做（两者无依赖，计划默认可插队；建议先做 ImageCuratorAgent 看产品效果）。
5. 推理滑杆的 `reasoning_content` 回传采用 A/B/C/D 哪个方案（需先跑 2 轮工具调用探针）。
6. `max-tokens` 是否从 32768 上调（建议实测 64K；`max_tokens` 是上限，不直接增加费用）。

---

## 十、执行状态（2026-10-18）

- [x] 正式域名、`deepseek-flash`、64K 输出上限已切换并完成真实调用。
- [x] `AgentToolLoop`、`SiteRepairAgent`、`SiteModifyAgent` 已落地；`VueProjectAgent` 从 895 行降到 269 行。
- [x] 四档推理滑杆已接入后端、SSE 请求与前端，并完成 off/low/high/max 真实探针。
- [x] `ImageCuratorAgent` 两轮视觉筛选、严格 JSON、失败降级、`materials.json` 已实现；默认启用，可通过环境变量关闭。
- [x] 后端常规测试 70 个（56 通过、14 个探针跳过），前端生产构建通过，真实无图生成通过。
- [x] COS 恢复后完成图片落地全链路：Pexels/Pixabay 按独立视觉主题检索并轮转取样；最终图片数量由模型按页面复杂度决定，Curator 关闭时的程序兜底才使用 16 张限制。
- [x] 3 档渲染复验通过；使用 Edge 155 匹配驱动，并修正隐藏空 `src` 图片的误报。
- [x] 上下文语义压缩已落地：超过 8K tokens 后压缩较早历史，最近 4 轮保留原文，摘要缓存支持增量更新。
- [x] Prompt v2 已完成公共规则收敛、素材先行冲突修正、Vue 修复后缀修正和动态数据信任边界加固。
- [x] DeepSeek `searchWeb` / `fetchWeb` 两个工具已接入，默认关闭，包含限速、重试、缓存、正文截断和不可信数据边界。
- [x] 图片布局规则已加固：竖图默认保留原始比例，禁止无意义固定比例裁切；用户指出的 `appId=469427600224477184` 已完成 3 档渲染复验。
- [x] 多主题选片已修复：模型为每个明确主题生成独立英文 query，候选按主题轮转进入视觉池；六主题探针确认 Cosplay 等主题均进入最终素材。
- [x] 长任务 SSE 已加固：30 分钟超时、15 秒心跳、断线禁止自动重复生成，前端轮询应用状态恢复最终产物。

详细数据见 `tmp/agent-decoupling-verification.md`。
