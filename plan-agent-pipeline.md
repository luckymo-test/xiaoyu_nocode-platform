# 计划书：生成 Agent 的流水线改造（对照外部 Agent 能力）

> 日期：2026-10-08
> 背景：用另一个 agent 产品完成同一需求（生成"含真实钢笔照片"的页面），其完成逻辑明显更严谨。本文对照其 7 阶段链路与当前 `VueProjectAgent` 的实现，定位差距根因，给出改造方案。
> 现状基线：提交 `571d295`。相关配套改动见 [plan-refactor.md](plan-refactor.md)。

---

## 一、对照对象：外部 Agent 的 7 阶段链路

| 阶段 | 目标产出 |
|---|---|
| 0 能力盘点 | 确认能不能生图、工作区里有什么 → **否掉了 AI 生图路线** |
| 1 图源探测 | 找到一个**网络可达且授权干净**的真实图片源（探测约 15 个站点） |
| 2 素材获取 | 批量抓取真实钢笔照片（793 个候选 → 234 张下载） |
| 3 素材筛选 | 人眼判定画质、构图、有无水印（50 张合格 → 20 张入围） |
| 4 素材加工 | 裁切、压缩、统一命名（11 张成品，页面用 10 张） |
| 5 页面构建 | 单文件 HTML + 设计系统（index.html） |
| 6 验证交付 | 无头浏览器实拍验证并修 bug（3 档分辨率全通过） |

它的关键特征不是"工具多"，而是**流程纪律**：每个阶段有名字、有可测量的产出、有质量闸门，且**素材先于页面**。

---

## 二、当前实现的真实链路（证据）

入口链路：`AiChatController` → `AiCodeGeneratorFacade` → `VueProjectAgent`。

`VueProjectAgent.generate`（[VueProjectAgent.java](src/main/java/com/mofanyi/ai/VueProjectAgent.java)）的实际步骤：

1. `appImageMapper.deleteByAppId` 清旧图
2. `runToolLoop` —— 写页面（`writeFile` / `readFile` / `searchImage` / `diagram` / `finish`）
3. `autoFillImages` —— **事后**正则扫描并补图
4. `scanAndSaveImages` —— 正则扫图 URL 落库
5. `assembleResult` —— 组装结果

其中：

- **图片候选池极小**：[PexelsClient.java](src/main/java/com/mofanyi/core/PexelsClient.java) 的 `pageSize` 默认 5；[SiteImageService.java](src/main/java/com/mofanyi/core/SiteImageService.java) 一次搜索最多 5 张候选、全部下载上传 COS。
- **无质量闸门**：5 张候选直接交给模型"自己挑"，无分辨率/构图/水印/去重判据。
- **无素材加工**：`downloadAndUpload` 原样上传原图，后缀硬编码 `.jpg`，UUID 命名，无裁切/压缩。
- **素材事后补**：`autoFillImages` 在页面写完后才用正则填 `image: ""` 等占位（优化的是"不为空"，不是"好"）。
- **无渲染验证**：项目里唯一的浏览器渲染是 [CoverImageService.java](src/main/java/com/mofanyi/core/CoverImageService.java)，它跑在**部署之后**、只为生成封面缩略图，截图结果**从不回灌**生成链路；vue 的 [VueBuildService.java](src/main/java/com/mofanyi/core/VueBuildService.java) `fixBuildError` 修的是**编译错误**，不是渲染正确性。

结论：当前 html / multi_file 链路本质是 **"一次工具循环生成页面 → 事后正则补图"**，是**开环**的。

---

## 三、逐阶段对照

| 外部 Agent 阶段 | 当前对应实现 | 差距 |
|---|---|---|
| 0 能力盘点 → 否掉 AI 生图 | **无**，直接进 tool loop | 缺 preflight 与路线决策 |
| 1 图源探测（探 15 站，选可达且授权干净的源） | 硬编码 Pexels + Pixabay 回退 | 无来源探索、无可达性校验 |
| 2 素材获取（793 候选 → 234 下载） | `pageSize=5`，一次最多 5 张候选 | 候选池小**两个数量级** |
| 3 素材筛选（234→50→20，判画质/构图/水印） | 无闸门，5 张丢给模型自选 | 无质量判定标准 |
| 4 素材加工（裁切/压缩/统一命名，20→11） | 原样上传、后缀写死 `.jpg`、UUID 命名 | 无裁切/压缩/命名规范 |
| 5 页面构建（HTML + 设计系统） | prompt 含设计规范，模型一次生成 | 差距较小 |
| 6 验证交付（无头浏览器实拍，3 档分辨率全过） | **无**（唯一渲染在部署后，且不回灌） | **最大缺口：无交付前验证** |

---

## 四、五个结构性根因

### 4.1 顺序反了：素材先行 vs 页面先行

外部 Agent：阶段 2-4（素材）在阶段 5（页面）**之前**完成，页面围绕已知素材（尺寸、宽高比、数量）设计。

当前实现：`runToolLoop`（页面）先跑，`autoFillImages`（素材）事后补。页面是在**不知道有哪些素材**的情况下写出来的，必然出现 `image: ""` 占位，再让正则去猜。development-notes 反复出现的"页面 0 张图"（#57/#59/#66），本质就是这个顺序造成的。

### 4.2 把"工具"当成了"流程"

当前有工具（`searchImage`/`writeFile`/`diagram`/`finish`），但没有**流程**——"怎么用工具"整个交给模型一次作答。外部 Agent 的是一套与工具解耦的**方法论**（研究→获取→筛选→加工→构建→验证），每阶段有可测量产出。后果：质量取决于模型这一次发挥，不可复现、不可检查。

### 4.3 只有生成，没有验证回路

外部 Agent 阶段 6 是闭环：实拍 → 修 → 再拍，直到 3 档分辨率全过。当前 html/multi_file 是**开环**；图裂、布局错、0 图这些问题一直是**人**在浏览器里发现的（development-notes 即证据），agent 自己无从察觉。

### 4.4 质量是"事后兜底"而非"过程闸门"

外部 Agent 是一条漏斗 793→234→50→20→11→10，每次收窄都有判据。当前 `autoFillImages` 是一次性正则 + `MAX_FILL=8`，"填到不为空为止"——**优化的是"不空"，不是"好"**。

### 4.5 失败是"静默降级"而非"显式决策"

外部 Agent 阶段 0 明确**否掉**一条路线（fail-closed）。当前是 Pexels → Pixabay → 纯色/渐变一路静默回退（fail-open）。#66 的 bug 即典型：Pexels 鉴权出问题，静默返回空，页面悄无声息变成 0 图，很久才被发现。

---

## 五、不照抄的部分（风险与成本）

外部 Agent 的具体做了**不**等于都该抄：

1. **"探测 15 个站点然后抓取"有法律风险**。它自称"授权干净"，但抓取真实照片是否真授权需打问号。当前用 Pexels/Pixabay **授权干净的 API**，这点反而更稳。要学的是流程纪律，不是"爬站"手法。
2. **重流程成本高**。探 15 站 + 下 234 张 + 筛 50 张只为 10 张成品——当"图片质量就是核心交付物"时值得，平台批量生成时不可承受。**筛选深度应自适应**：只有图片主导型页面才走重流程。
3. **"人眼判定"不可字面复制**。人工会话里"人眼"是人；自主 agent 里要用**视觉模型**或**程序化启发式**替代。

---

## 六、改造方案

### 6.1 目标流水线

```
Preflight(能力盘点：图源可达? node 可用? COS 可写?)
   │
   ├─ 需要图片? ── 是 ─▶ 素材获取(多 query，大候选池)
   │                        └▶ 质量闸门(分辨率/宽高比/去重/水印/视觉打分)
   │                             └▶ 加工规范化(裁切/压缩/命名) ─▶ 素材清单
   │                                                                    │
   └───────────────────────────────┬───────────────────────────────────┘
                                    ▼
                              页面构建(围绕素材清单)
                                    ▼
                              渲染验证(3 档视口) ──有 问题? ──▶ 修复 ──▶ 复验
                                    ▼ 通过
                                  交付(落盘 / 部署 / 封面)
```

### 6.2 交付前渲染验证（最高价值，P0）✅ 已落地

复用已有 Selenium 依赖（`CoverImageService` 已用 Chrome/Edge + headless）：

1. 无头浏览器渲染生成的 `index.html`；
2. 3 档视口：桌面 1440 / 平板 768 / 手机 375；
3. 采集结构化问题清单：图裂（`img.naturalWidth===0`）、资源 404、console error、横向溢出（`scrollWidth > clientWidth`）、空容器、每档截图；
4. 清单喂给修复 agent（把 `fixBuildError` 从"编译错误"泛化为"渲染错误修复"），修完**重新渲染**，最多 N 轮。

**接入点**：生成流程末尾（`autoFillImages` 之后或替换它）；与 vue 的构建失败修复并列，构成统一的"验证 → 修复"回路。

**落地状态：✅ 已实现并实测**

- 新增 `core/BrowserFactory`（抽取驱动创建，与 `CoverImageService` 共用）与 `core/RenderVerifier`：
  1. 起**一个**无头会话，逐视口 `setSize` 到 1440 / 768 / 375（比每档各起一次会话快得多）；
  2. 页面内取证：图裂/空 `src`、横向溢出（`scrollWidth > clientWidth`）、可见文本过少（疑似空白页）、控制台严重错误；
  3. 报告落盘 `.agent/verify.json`（阶段产物，可追溯）；
  4. 有问题则把报告文本喂给 `VueProjectAgent.fixRenderIssues`（新增 `codegen_render_fix_prompt.txt`），修完**复验**一次。
- 接入 `AiCodeGeneratorFacade` 的 3 条生成路径；开关 `codegen.verify.enabled`（默认 true）。
- **失败不阻断交付**：浏览器不可用、验证异常、修复失败都只记日志，生成结果照常返回。
- 实测：正常页面「3 档视口全部通过」；故意构造的页面（坏图 + 2000px 宽元素）「3/3 档视口存在问题」，图裂与横向溢出均被检出。

> 局限：通过 `file://` 加载，ES module / `fetch` 类页面可能加载失败；渲染修复的进度暂未推到前端。
> 附带修复：本机 `msedgedriver`(151) 与 Edge(153) 版本不匹配，已下载匹配驱动到 Selenium 缓存——该链路此前也是坏的（影响封面截图）。
> **补记（2026-10-10）**：上面只把驱动**下载**到了缓存，**配置兜底仍指向 151** —— 所以只要不设 `SCREENSHOT_DRIVER_PATH`，
> 链路依旧是坏的。实测证据：`SessionNotCreatedException: session not created: This version of Microsoft Edge WebDriver
> only supports Microsoft Edge version 151 / Current browser version is 153.0.4234.32`（`RenderVerifierTest` 因此长期
> "2 skipped"）。已把 `application-location.yml` 与其 `.example` 的兜底改为 `153.0.4234.32`。改后不设环境变量也跑通：
> 真实生成 `[verify] 渲染验证完成：1/3 档视口存在问题` → 异步修复 → `复验：3 档视口全部通过`；`mvn -o test` 由
> "37 用例 / 2 skipped" 变为 **"37 用例 / 0 skipped"**。

### 6.3 素材先行（P1）

> **已完成（2026-10-10）**：`ImageMaterialPlanner` 落地上表 1~3 条（多 query 候选池 → 闸门 → top-K 上传 →
> 清单注入页面构建 prompt → 删掉 `ImageAutoFiller` 正则补图）。第 4 条（素材加工）留给 6.5。
> 实测：新 app + 图片主导 prompt，8 幅作品 src **全部**来自清单、模型未再自行搜图、全链路复验 3 档通过。
> 细节与待办见 [plan-refactor.md](plan-refactor.md) 6.6。

1. 需要图片时**先生成素材清单**：多 query 扩大候选池（5 → 20~30），加质量闸门，选 top-K；
2. 把清单（url / 宽高 / 用途）作为事实注入**页面构建** prompt；
3. **删除 `autoFillImages` 正则补图**；
4. 素材加工：按目标宽高比裁切、压到 web 尺寸、统一命名。

### 6.4 候选池扩容 + 质量闸门

候选从 5 张扩到几十张（多 query、加大 `pageSize`），闸门判据：最小分辨率、宽高比分桶、感知去重（perceptual hash）、水印启发式、可选视觉模型打分。

### 6.5 素材加工规范化

按目标宽高比裁切、压到 web 尺寸、统一命名并记录尺寸（现状是原图直传、后缀硬编码 `.jpg`）。

### 6.6 Preflight 能力盘点

生成前探测 Pexels/Pixabay 可达性、node 是否可用（vue）、COS 是否可写，把结果作为**事实**喂给模型，避免依赖一个已经挂掉的能力。

### 6.7 阶段产物与可观测

每次生成落一份 `manifest.json`（各阶段候选数、入选、被拒原因），并在 SSE 上加阶段事件——接上已有的可观测性工作。

---

## 七、验收

1. 故意生成引用不存在图片地址的页面 → 验证阶段报出"图裂"并进入修复。
2. 3 档视口渲染无异常时才置 `done`、发 `done` 事件。
3. 图片主导页面：页面中图片 URL 全部来自**预选素材清单**。
4. `autoFillImages` 相关代码与正则被移除。
5. 图源不可达时给出**显式**提示，而非静默降级为纯色。

---

## 八、分期与依赖

| 期 | 内容 | 依赖 |
|---|---|---|
| 一期 ✅ | 交付前渲染验证（P0） | 无（Selenium 已在） |
| 二期 | 素材先行 + 质量闸门 + 加工规范化 | 一期验证回路（用于复核页面） |
| 三期 | Preflight 能力盘点 + 阶段产物可观测 | 前两期 |

> 一期必须先行：它是后续"素材先行"改动的**安全网**——改了素材链路后，靠渲染验证确认页面没退化。

---

## 九、暂不做

- 不引入"爬取任意站点"的图源策略（法律风险）；图源仍以授权 API 为主。
- 不对所有页面启用重流程筛选（仅在图片主导型页面启用）。
- 不引入独立视觉大模型服务（先用程序化启发式 + 现有能力，视效果再评估）。
