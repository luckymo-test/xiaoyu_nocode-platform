# 排查报告 & 修改计划：多文件流式生成卡顿 / 图片搜索"失败"

> 日期：2026-09-06
> 状态：仅排查 + 计划（未实施）

---

## 一、现象（以后端日志为证）

多文件流式生成（appId=453876979039789056）两次实测：

| 时间点 | 事件 |
|---|---|
| 请求发起后 ~1 分钟 | `AsyncRequestTimeoutException`（SSE 300s 超时的伴随现象） |
| 00:12:03 / 00:18:35 | 后端 `[scan] 扫描到 0 个图片 URL` —— 表示该轮 runToolLoop **跑完**，耗时约 **5~6 分钟** |
| 同时刻 | `保存 AI 对话历史失败` —— 因为 SSE 已被服务端超时掐断，done/历史写不进去 |

- 全程 `AsyncRequestTimeoutException` 共 **6 次**（用户多次测试都卡在 5 分钟）。
- 图片相关：全程 **0 条** Pexels / COS / 图片错误日志；产物里 0 张图。

---

## 二、根因分析

### A. 生成本身过慢（后端，主因）

**A1｜推理模型每轮"过度思考"**：`deepseek-v4-flash` 每次模型调用都先产出一大段 reasoning 再做工具调用（此前修改/生成实测单轮 reasoning 可达 **7k~1.4 万 token**）。多文件 = 多个工具轮（index/css/js/…/finish），每轮一次慢推理，叠加后总时长轻松超过 5 分钟。

**A2｜上下文随文件增长越滚越大**：框架的 tool-loop 每轮把**整个消息历史**（含此前 writeFile 工具调用里**嵌入的完整文件内容**）全部重发给模型 → 文件越多上下文越大 → 每一轮比上一轮更慢 → 直观"越后面越卡"。

**A3｜SSE 300s 硬超时把"慢"变成"失败"**：`SseEmitter(300_000L)` 一到 5 分钟就抛 `AsyncRequestTimeoutException`，前端连接被服务端掐断 → 表现成卡死/失败；而后端 worker 仍在跑（scan 在 +6min 才出现），`done` 发不出去、对话历史也存不上。

> 三者叠加：生成慢（A1+A2）→ 撞超时（A3）→ 前端断连无反馈 = 用户看到的"卡死"。

### B. 前端 reasoning 渲染 O(n²)（前端，次因，也是"越后越卡"的直接来源）

- 每个 reasoning 增量做 `aiMsg.reasoning = (aiMsg.reasoning || '') + text` —— 整串几千 token **每次全量重赋值**；
- 模板用 `<MarkdownView :content="msg.reasoning">` —— MarkdownView 是 `md.render(props.content)` 的 computed，**整串每次全量 markdown 解析 + 语法高亮**；
- 数千个增量 × 每次都全量渲染 = **O(n²)**，主线程被占满 → 浏览器卡死。reasoning 越长（越靠后）越卡，与用户描述完全吻合。

### C. 图片搜索"失败"

- **Pexels API 与 key 实测正常**：三种调用变体（无 orientation / 空 orientation / landscape）全部 HTTP 200 且返回图。
- 但 [PexelsClient.java:51](src/main/java/com/mofanyi/core/PexelsClient.java:51) 的失败模式是**静默**的：
  - 只 `execute().body()` 取响应体，**不检查 HTTP 状态码**；401/403/429/5xx 时 body 是错误 JSON，`photos == null` → **直接返回空列表，无任何日志**；
  - 于是 searchImage 工具向模型返回"未找到匹配的图片…"，模型按 [codegen_multifile_prompt.txt:27](src/main/resources/prompt/codegen_multifile_prompt.txt:27) 的兜底规则 **"搜索失败或无结果 → 用纯色/渐变/CSS 装饰替代图片，不用不存在的链接"** → 整页无 `<img>` → scan 0 张图，且全程无报错。
- 次要可能：慢/卡导致模型在有限轮次里没走到 searchImage（需打点确认）。

---

## 三、修改计划

### 第一步：先量化定位（1 个半天）
1. `VueProjectAgent.runToolLoop`/`streamAndAggregate` 加轻量计时与计数日志：每轮耗时、reasoning 增量 token 数、本轮请求字符数、searchImage 调用次数与返回结果。
2. 据此确认 A1（推理烧时）与 A2（上下文膨胀）各自占比，再决定是否做下面的"自管 tool-loop"。

### 第二步：后端超时与体验（低风险，先做）
1. **SSE 超时从 300s 提到 600s+**（`AiChatController` 各 stream 的 `SseEmitter`），避免正常慢生成被掐断；同时给生成过程持续推心跳/进度（已有 agent_progress），让浏览器不判超时。
2. `saveAssistantMessage` 失败只是降级警告——可接受，但把"SSE 已超时才 done"的情况单独记 info，便于区分。

### 第三步：抑制"过度推理"（中风险）
1. 各生成/修复/修改 prompt 顶部加一句强约束："思考要简短直接，定位到目标后立刻调用工具，不要长篇展开推理。"——降低每轮 reasoning 量。
2. 评估工具循环是否换用**更快/非推理**的模型接口（若平台提供），或允许 `?noReasoning=1` 让工具循环走非流式 `call()`（不透传 reasoning）以获得吞吐。若切换，注意前端 reasoning 展示会退化，需同步。

### 第四步：前端渲染修复（必做，低风险）
1. **reasoning 不再用 MarkdownView 全量重渲染**：
   - 方案：渲染成**纯文本**（普通 `<div style="white-space:pre-wrap">` 或 `<pre>`），限高滚动、等宽小字；文本节点增量 append，避免整串重赋值。MarkdownView 只留给 AI 正文。
   - 更彻底：生成期间只显示"思考中…"脉冲，reasoning 默认不展示（符合之前"文字太多体验差"反馈）。
2. 正文/文件清单渲染保持现状（文件清单是 push 数组项，量小无 O(n²)）。

### 第五步：图片链路修复（低风险）
1. `PexelsClient.searchImages`：**检查 HTTP 状态码**；非 200 记 `log.warn("Pexels 搜索失败 status={} query={}", code, query)` 并把失败原因以异常/可读结果抛回，让 searchImage 工具把真实原因带给模型（如"Pexels 限流 429，暂改用渐变占位"），而不是模型以为"没图可搜"。
2. `searchAndUploadImages` 对单张失败已 catch，保持；给"整次调用失败/空"也留一条 info，便于观测。
3. 打点确认模型是否真的调用 searchImage（见第一步），必要时在多文件 prompt 里补一句"**先 searchImage 再写对应 HTML**"的顺序约束。

### 第六步：可选大改造（A2 根治，收益需与第一步数据对照）
- 放弃框架 tool-loop 自动执行，改回**自管 tool-loop**（`runToolLoop` 去掉 `toolCallbacks` 自动执行，手动拼 history）：可对历史做裁剪/压缩（如只保留文件清单 + 被改动文件的引用，写文件时模型自带全文即可，不必每轮重发全部旧文件），从根上消除"上下文越滚越大"。改动大、回归风险高，需在第一步数据证明 A2 占比大后再做。

### 验证口径
- 单次多文件生成：目标 ≤ 120s 完成且全程有进度推送、不被掐断；reasoning 事件不再拖垮浏览器（控制台无长时间卡顿）。
- 图片：一次含配图需求的多文件生成，日志出现 searchImage 调用 ≥1 且产物含真实 COS 图 URL，scan > 0。

## 四、工作量
| 步骤 | 内容 | 相对工作量 |
|---|---|---|
| 1 | 计时/打点 | ★ |
| 2 | SSE 超时 + 心跳 | ★ |
| 3 | 抑制推理 + 可选换模型 | ★★ |
| 4 | 前端 reasoning 纯文本/脉冲化 | ★★（必做） |
| 5 | Pexels 错误可见化 | ★ |
| 6 | 自管 tool-loop（根治 A2） | ★★★★（可选，先看第一步数据） |
