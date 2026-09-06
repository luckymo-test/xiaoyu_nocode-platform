# 计划书：AI 可视化修改（点选网页元素 + 自然语言微调）

> 日期：2026-08-18
> 目标：让用户能在已生成网站的**网页预览**上直接点击选中某个元素，用自然语言描述想怎么改，AI 精确修改对应源码并刷新预览，实现"看得见地改"。
> 现状依据：`AppGeneratePage.vue` 的网页预览是 `iframe srcdoc`（sandbox 含 `allow-scripts allow-same-origin`，父页面可访问 iframe DOM）；生成链路是 `VueProjectAgent`（agent + writeFile/finish 工具 + 逐文件回调），`CodeFileWriteService.saveProjectFile(type, appId, path, content)` 负责落盘；修改源码后 vue 需失效 dist 才会重建。

---

## 一、产品交互（UX 目标）

1. 用户在"网页预览"里点开「可视化编辑」开关 → 进入选择模式。
2. 鼠标移到元素上：元素高亮（描边），提示"点击选中要改的地方"。
3. 点击元素：选中并弹出编辑卡片（悬浮在预览上方），卡片显示：
   - 元素信息：标签 + class/id + 示例文本片段（自动生成）；
   - 输入框：预填模板，如「把选中的【标题】改成……」，用户补全或重写；
   - 快捷改法 chips：改文字 / 改颜色 / 改字号 / 换图片 / 调布局 / 加动效；
   - 「应用修改」按钮。
4. 点击应用 → 走 AI 修改（流式，卡片显示"正在修改 index.html…"）→ 完成后**自动刷新预览**和右侧代码文件树，改动即时可见。
5. 改动结果落回该 app 的源码（与重新生成一致），部署/下载取到的是改后版本。

> 编辑不打断对话主流程；「修改」本质是对已生成 app 的一次**精确补丁**，不是从头再生成。

---

## 二、整体架构

```
AppGeneratePage（网页预览 iframe，父页面同源可访问 DOM）
   │  ① 选择模式：注入高亮/点击捕获，生成元素上下文（selector + 结构摘要）
   ▼
  编辑卡片 → ② SSE POST /ai/modify/stream  { appId, type, elementContext, instruction }
后端 AiChatController → AiCodeGeneratorFacade.modifyByAgent(...)  （新方法）
   ▼
VueProjectAgent.modifyGeneratedSite(...)  （新方法，仿 fixBuildError）
   ├─ system = 【codegen_modify_prompt.txt】 + 全部源码文件内容
   ├─ user  = 元素上下文(selector/标签/文本/outerHTML 摘要) + 用户修改意图(中文)
   ├─ 工具：writeFile（覆盖目标文件）+ readFile（按需取精确源码）+ finish
   └─ runToolLoop 逐文件写入 → agent_progress 回调推前端
   ▼
落盘（saveProjectFile）+ 刷新预览：html/multi_file → 重新内联；vue → 删 dist 重建预览
```

---

## 三、关键难点与方案

### 1. 选中的网页元素如何"定位"到源码（核心）

**不追求 DOM→源码行号映射**（对 SFC/压缩后产物不可靠），改用"**结构化元素上下文 + AI 在源码里按特征定位**"：

- 前端为被点击元素生成上下文：
  - **CSS 选择器路径**：从元素向上到 body，优先 `#id` / `.class`，否则 `tag:nth-of-type(n)`，如 `section.hero > div.container > h1.title`；
  - **元素快照**：`tag`、`id`、`class`、`style`（内联样式）、文本内容（截断 ~200）、`outerHTML`（截断 ~600）；
  - **锚点**：向上找最近的带 `id`/唯一 class 的祖先，作 AI 定位参照。
- AI 修改 prompt 明确要求：按 class/id/文本/结构在**给定源码里找到产出该元素的代码段**，只改该段。
- html 单文件、multi_file 的源码与预览 DOM 高度一致，定位可靠；**vue 是构建产物，见难点 3**。

### 2. 大文件上下文塞不下 → 加 `readFile` 工具

现状 `fixBuildError` 把文件内容拼进 user 消息、>8000 截断。修改场景改为：
- 首轮只带**文件清单 + 元素上下文 + 意图**；
- 提供 `readFile(path)` 工具，模型按需读目标文件精确内容再改（比全量塞入省 token 且准）。

### 3. vue 模式映射与重建成本（重点风险，建议分阶段）

- vue 网页预览来自 **dist 构建产物**，DOM 与 `.vue` 源码非 1:1；点击元素靠 class/text 大概率能对上，但不够可靠；
- 每次改动 vue 都要触发 `npm run build`（VueBuildService），耗时且不稳定。

**决策**：阶段一（本计划范围）**只开放 html / multi_file 的可视化修改**；vue 模式编辑开关置灰或提示"该模式暂不支持可视化修改"。阶段三再评估 vue（见实施顺序）。

### 4. iframe 交互细节

- srcdoc + `allow-same-origin` → 父页面能直接 `iframe.contentDocument` 注入监听；
- 修改后 `previewDoc` 变化 → srcdoc 会整体 reload → **监听器需在 iframe `@load` 后重新武装**（抽 `armEditor(iframe)`，load 事件里调用）；
- 选择模式下要 `capture` 阶段拦掉点击/表单，避免误触发页面功能；链接用 `preventDefault`。

### 5. 防误改 / 可回退

- 修改前后保留一份**快照**（文件 map，内存/会话级），提供"撤销本次修改"恢复快照并刷新预览（低成本高价值）。
- agent 修改 prompt 强调：不改无关结构、保留其他文件/JS 钩子；必要时只改目标文件。

---

## 四、后端改动明细

| 文件 | 改动 |
|---|---|
| `resources/prompt/codegen_modify_prompt.txt` | 新增修改专用 system prompt（元素定位→最小改动→writeFile/readFile 规范→中文推理，见上） |
| `ai/AiServiceFactory.java` | 增加 `modifyPrompt` bean（加载上述文件），构造器注入 `VueProjectAgent` |
| `ai/VueProjectAgent.java` | 新增 `modifyGeneratedSite(type, appId, elementContext, instruction, VueProgressListener)`：仿 `fixBuildError` 组装消息 → `runToolLoop`；新增 `readFile` 工具（返回 CodeFileWriteService 读取内容） |
| `core/AiCodeGeneratorFacade.java` | 新增 `streamModifyProject(...)`：入参校验 → 线程池执行 → 落盘后 `done(vo)`；vue 不在此阶段支持 |
| `controller/AiChatController.java` | 新增 `GET /ai/modify/stream`（SSE，agent_progress/reasoning/done/error），仅 appId 合法且登录；**仅创建者**可改（仿现有 saveUserMessage 鉴权） |
| `CodeFileWriteService.java` | 无需大改；落盘复用 `saveProjectFile` |

**接口出参**：`done` 返回 `AiCodeGenResultVO`（含 files + usage），前端据此刷新预览文件树与内联文档，token 展示复用现有逻辑。

---

## 五、前端改动明细

| 位置 | 改动 |
|---|---|
| `api/aiStream.ts`（或新建 `aiModify.ts`） | `streamModifyProject(appId, elementContext, instruction, callbacks)` → SSE `/ai/modify/stream` |
| `utils/sse.ts` | 无新事件（复用 agent_progress/reasoning/done） |
| `AppGeneratePage.vue`（web 预览区） | 「可视化编辑」开关（html/multi_file 才亮）；`armEditor()` 注入高亮+点击捕获；`buildElementContext(node)` 生成上下文；选中后显示编辑卡片（element 信息 + textarea + chips） |
| 新建 `visualEditor.ts`（可选） | 抽离选择器生成、监听注入等纯逻辑，便于维护/单测 |

**编辑后刷新链路（复用现有）**：
- html/multi_file：收到 done 的 files → 更新 `previewFiles`/`selectedFilePath` → 用现有 `buildPreviewDoc` 重建 srcdoc → iframe reload → re-arm 监听；文件树与消息文件清单同步。
- （阶段一不做 vue 编辑，vue 编辑开关禁用。）

---

## 六、安全与校验

- 只允许**应用创建者**修改（与下载一致，`NO_AUTH`）；未登录 401。
- `elementContext`/`instruction` 长度限制（如 2000/500 字符），防滥用。
- 沿用 agent `validatePath`：只写相对路径、禁 `..`、禁绝对路径/盘符。
- 改完调用方记录一条 AI 对话历史（可选，复用 `chatHistoryService`），便于追溯改动。

---

## 七、实施顺序与验证（分阶段）

| 阶段 | 内容 | 验证 |
|---|---|---|
| **1A（后端核心）** | modify prompt + `VueProjectAgent.modifyGeneratedSite` + readFile 工具 + facade + `/ai/modify/stream` | curl 带 elementContext 与"把标题改成红色" → 看哪个文件被 writeFile 覆盖、内容正确；越权 appId 返回 403 |
| **1B（前端交互）** | 编辑开关 + 元素选择高亮 + 上下文生成 + 编辑卡片 + SSE 应用 + 刷新预览（html/multi_file） | 浏览器实测：hover 高亮、点元素弹卡片、改文字/颜色后预览即时更新；改完代码树文件内容已变 |
| **1C（健壮性）** | 撤销快照、iframe 重新武装、多元素选择重试、instruction 失败时友好报错 | 连续改 3 次、故意改不存在元素、点链接/表单应被拦截不跳转 |
| **2（可选，待验证）** | vue 可视化编辑（dist 映射 + 每次改触发重建） | 单独 spike，验证 class/text 定位到 .vue 源码的成功率 |

**关键风险与缓解**
- 元素定位不准（同 class 多处）→ 上下文里给 `nth-of-type` 路径 + outerHTML 片段双保险；失败提示用户换更具体描述。
- iframe reload 丢监听 → `@load` re-arm。
- vue 映射/重建成本高 → 阶段一不做，明确 UI 禁用提示。
- agent 改多/改错 → 快照撤销 + prompt 强约束最小改动。
- 大文件截断 → 用 readFile 工具按需读，避免首轮全量塞入。

## 八、工作量评估（相对）

| 项 | 工作量 |
|---|---|
| 1A 后端 agent + 接口 | ★★★（readFile 工具 + 新 prompt + 新 endpoint 都不难，复用 runToolLoop） |
| 1B 前端选择交互 | ★★★★（iframe 注入监听、选中体验、卡片 UI 是重点） |
| 1C 健壮性 | ★★ |
| 2 vue 支持 | ★★★★★（单独评估，性价比待验证） |

建议先做 1A，交付后端接口 + curl 验证后，再做 1B 前端交互；1C 打磨放最后。vue 的可视化修改单独排期并先出 spike 结论。
