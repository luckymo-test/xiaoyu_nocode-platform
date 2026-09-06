# 计划书：AI 生成对话与代码预览改造

> 日期：2026-08-17
> 范围：`AppGeneratePage`（生成对话页）+ 后端流式链路
> 涉及四个目标：对话不显示完整代码（点文件查看）、流式展示思考过程 + token 元信息、代码预览分文件展示、已部署应用增加"打开部署网址"按钮

---

## 现状梳理

| 项 | 现状 |
|---|---|
| 对话消息内容 | AI 消息体 = description + **全部文件代码块**（`#### path` + ```js```），vue / html / multi_file 都是拼在消息里 |
| 预览面板 | vue 有文件树 + 单文件查看；html / multi_file 只有 `formattedCode` 拼接的**一块**大代码 |
| 后端流式 | SSE 只推 `agent_progress`（每文件 `{"path","content"}`）+ `done`（VO）+ `error` |
| 模型调用 | `VueProjectAgent.runToolLoop` 用 `chatModel.call()`（**非流式**），不拿 reasoning、不统计 token |
| 部署入口 | 顶部按钮：未部署=`部署`，已部署=`下线`；**没有跳转部署网址** |
| 部署 URL | 后端 `AppServiceImpl` 拼 `domain + "/" + deployKey`，AppVO 只暴露 `deployKey`，前端拿不到完整 URL |

---

## 一、对话只显示文件清单，点击查看完整代码

**目标**：AI 气泡里不再刷一长串代码，改成"生成了哪些文件"的文件清单；点击某个文件 → 在右侧预览面板打开该文件完整代码。

### 方案

1. **消息模型扩展**（前端）
   - `ChatMessage` 增加 `files: { path: string; content: string }[]`。
   - 实时生成时：`agent_progress` 每收到一个文件就 push 进 `aiMsg.files` 和全局 `currentFiles`；`aiMsg.content` 只保留 description（或留空），不再拼接代码。
   - 历史消息回显：`toChatMessage()` 里把存库的 markdown（`#### path\n```lang\ncontent\n``` 结构，见 `AiChatController.buildAssistantContent`）**解析回 files 数组**，保证老消息也能点文件看代码。

2. **气泡渲染**（前端，`msg-bubble`）
   - AI 气泡内：description 文本 + 文件清单组件（每个文件一行：文件名 + 文件类型小图标 + 大小）。
   - 点击文件行 → 调用 `selectFile(path)`，把右侧预览面板切到"代码预览"并选中该文件。

3. **数据流**（不变的后端，纯前端改造）
   - 生成中：`agent_progress` → `aiMsg.files` 累加 → 气泡里的清单实时增加条目。
   - 完成后：`done` 的 `result.files` 覆盖 `aiMsg.files`，保证最终一致。

### 涉及文件
- `xiaoyuZeroCode-front/src/views/app/AppGeneratePage.vue`（消息模型、reveal 逻辑改为"清单累加"、新增文件清单组件/点击跳转）
- `xiaoyuZeroCode-front/src/components/`（可抽一个 `FileList.vue` 通用组件，AdminChatPage 复用）

---

## 二、流式展示 AI 思考过程 + token 元信息

**目标**：AI 生成时能看到它的"思考过程"（reasoning）流式滚动；结束展示本次消耗的 token 数等元信息。

### 现状阻碍
- `runToolLoop` 用 `chatModel.call()`（阻塞、非流式）→ 拿不到推理增量。
- Spring AI 1.1.8 对 DeepSeek 的 `reasoning_content` 是否映射进标准消息**未确认**（DeepSeek 走 OpenAI 兼容接口，`reasoning_content` 非 OpenAI 标准字段）。

### 方案（分两步，风险隔离）

**步骤 2A：token 元信息（低风险，先做）**
- 后端：`VueProjectAgent` 在 `runToolLoop` 每轮 `chatModel.call()` 后读取 `resp.getMetadata().getUsage()`，把 `promptTokens + completionTokens` 累加。
- 结束信号：`done` 事件不再只推 VO，改为推 `{ vo, usage: { promptTokens, completionTokens, totalTokens } }`（VO 增加可空 usage 字段，兼容老前端）。
- 前端：AI 气泡底部展示 `消耗 ≈ X tokens（输入 Y / 输出 Z）`，灰色小字。

**步骤 2B：思考过程流式（中高风险，先做 spike）**
- Spike：写一个临时接口用 `chatModel.stream()` 跑一次，确认 Spring AI 1.1.8 能否从流式 chunk 里拿到 `reasoning_content`。若不行，需自研 OpenAI 兼容流式解析（或抓原始响应），这是最大的技术风险点。
- 若可行：`runToolLoop` 的 `chatModel.call()` 改 `chatModel.stream()`；新增 `reasoningListener` 回调，把 reasoning 增量逐段推给前端（SSE 新事件 `reasoning`，data 为文本增量）。
- 前端：AI 气泡内新增可折叠"思考过程"区（`<details>` 或显隐按钮），流式期间光标滚动，结束时收起并保留。**默认折叠**，避免喧宾夺主。
- 注意：reasoning 增量可能与 `agent_progress` 交替到达（思考一段→写一个文件→再思考），前端按到达顺序追加即可。

### 涉及文件
- 后端：`VueProjectAgent.java`（stream 化 + usage 统计 + reasoning 回调）、`AiCodeGeneratorFacade.java`（接 reasoning 回调 → SSE）、`AiChatController.java`（新事件推送）、`AiCodeGenResultVO`（加 usage 字段）
- 前端：`sse.ts`（`reasoning` 事件）、`aiStream.ts`、`AppGeneratePage.vue`（reasoning 渲染 + token 展示）

---

## 三、代码预览分文件展示

**目标**：预览面板不再是一大块代码，而是**文件树 + 单文件内容**，html / multi_file 与 vue 统一。

### 方案
1. 把 vue 已有的文件树布局（`vue-file-tree` + `vue-code`）**推广到所有类型**：
   - vue：用现有 `result.files`。
   - multi_file：用 `result.files`（后端 multi_file 也返回 files，前端当前只用 htmlCode/cssCode/jsCode 拼接，改成用 files 树）。
   - 单文件 html：文件树只有一项 `index.html`。
2. `currentFiles` 单一数据源（feature 一 引入），预览面板统一读它渲染；选中文件 = `selectedFilePath`，两侧联动（气泡点文件 → 预览选中；预览点文件 → 高亮）。
3. 删除 `buildFormattedCode`（拼接大代码块）逻辑，保留 `buildPreviewDoc`（网页预览仍需要）。

### 涉及文件
- `AppGeneratePage.vue`（预览面板重构为通用文件树，删除拼接逻辑）
- 可选：抽 `CodeFileTree.vue` 组件

---

## 四、已部署应用增加"打开部署网址"按钮

**目标**：已部署的应用重进对话页，顶部除了"下线"，还能一键跳转已部署的网站。

### 方案
1. 后端：`AppVO` 增加 `deployUrl` 字段；`AppServiceImpl` 组装 VO 时，若 `deployKey` 非空则填 `codeFileWriteService.getDomain() + "/" + deployKey`（与 `doDeploy` 返回的一致）。
2. 前端：顶部按钮区，当 `app.deployKey` 存在时显示 `打开网站` 按钮（`window.open(app.deployUrl)`），放在"下线"旁。`app.deployUrl` 为空（老数据/配置缺失）时按钮禁用。
3. `getAppVoById` 返回后 `app.value.deployUrl` 即可用，无需额外请求。

### 涉及文件
- 后端：`AppVO.java`、`AppServiceImpl.java`
- 前端：`AppGeneratePage.vue`（顶部按钮）
- 前端类型：`typings.d.ts`（openapi2ts 重新生成 AppVO 类型，注意跑完检查手写文件是否被删——见 code_rule.md）

---

## 五、实施顺序与验证

按依赖顺序分四批，每批可独立上线：

| 批次 | 内容 | 验证方式 |
|---|---|---|
| 1 | 四（部署网址按钮） | 部署一个 app → 重进对话页 → 点"打开网站"能打开 |
| 2 | 一 + 三（消息文件清单 + 预览分文件） | 生成 vue / multi_file / html 各测一次：气泡只显示清单、点文件能看代码、预览是文件树；**历史消息回显**也要能点文件 |
| 3 | 2A（token 元信息） | 生成完成后气泡底部出现 token 数，数值合理 |
| 4 | 2B（思考过程流式） | 生成时"思考过程"折叠区流式滚动；与文件进度交替正常；**spike 先行** |

### 关键风险
1. **2B 的 `reasoning_content` 映射**是最大不确定点——若 Spring AI 1.1.8 不透出，需自研解析或降级为"只显示 token 数"。spike 优先。
2. **历史消息解析**：老消息存的是 markdown，解析成 files 的健壮性要测（含代码块里有 `####` 的边界）。
3. **openapi2ts 会删手写文件**（如 `aiStream.ts`）——改完接口类型后跑生成，用 `git status src/api/` 检查并恢复。
4. 多文件实时生成时，气泡清单与预览树的数据一致性（同一 `currentFiles` 源，避免两处各存一份漂移）。

## 六、工作量评估

| 批次 | 后端 | 前端 | 相对工作量 |
|---|---|---|---|
| 1 | 小（AppVO + 1 处组装） | 小（1 个按钮） | ★ |
| 2 | 无 | 中（消息模型 + 预览面板重构 + 历史解析） | ★★★ |
| 3 | 小（usage 累加 + VO 字段） | 小（一行元信息） | ★★ |
| 4 | 大（stream 化 + reasoning 透传，含 spike） | 中（reasoning 折叠区） | ★★★★ |

建议先做批次 1（快速见效、零风险），批次 4 的 spike 可与批次 2 并行推进。
