# 计划书：图片获取能力扩展 —— undraw 插画 + Mermaid 架构图

> 日期：2026-09-06
> 目标：让"配图"不再只靠 Pexels 摄影图，而是**根据网站风格自动选择图源**：插画/扁平风格走 undraw 插画，架构/流程/时序类示意图由 AI 生成 Mermaid 文本并渲染成图。
> 现状：图片链路已集中在 [core/SiteImageService.java](src/main/java/com/mofanyi/core/SiteImageService.java)（Pexels 搜索→下载→COS→落库）+ [ai/tools/SearchImageTool.java](src/main/java/com/mofanyi/ai/tools/SearchImageTool.java)，生成与补图（autoFillImages）都复用它。改造相对集中。

---

## 一、调研结论（先摆事实，决定方案走向）

1. **undraw 没有稳定、官方的"关键词搜索图片"接口**——其搜索端点被反复弃用/失效（有开发者专门写过"失效后的替代方案"）。图片本身托管在 **cloudinary**，每张插画一个固定 slug（如 `business-deal`），可拼出直链。→ **不能做成"像 Pexels 一样现场按词搜索"**，应改成"**本地精选素材库 + 直链/存 COS**"。
2. **Mermaid CLI（mmdc）** 是 npm 工具，把 Mermaid 文本渲染成 png/svg/pdf，但依赖 **puppeteer/Chromium**（首次需下载或用本机 Chrome）。本机 node 可用（`D:\computer tools\nodejs`），但"渲染进程 + Chromium"是一个新环境依赖，需先在 spike 验证本机可行与耗时。
3. 备选（若 mmdc 装不上）：`kroki.io` 等远程渲染 API（发文本回图，需外网）——省去 Chromium，但依赖外网服务。写进"待定 A/B"。

---

## 二、方案设计

### 总原则：风格 → 图源 由模型自选，来源统一收敛回 COS URL

现在页面里的 `<img src>` 都是 COS URL（`SiteImageService` 上传后返回）。扩展后，**任何图源最终都返回 COS URL**，对代码/前端零改动——只是"取图的服务"从一个变成多个，按风格路由。

### 风格 → 图源 默认映射（写进生成/修改 prompt，让模型按场景选）

| 网站/内容风格 | 推荐图源 |
|---|---|
| 写实场景 / 人物照片 / 产品照 | **Pexels（主）→ Pixabay（Pexels 失效/无结果时自动回退）** |
| 扁平 / 插画 / 可爱 / 品牌主视觉 / 产品功能示意 | **undraw 插画** |
| 架构图 / 流程 / 时序 / ER / 思维导图 / 组织结构 | **Mermaid 渲染图** |

> 触发方式：把 `searchImage` 扩展为带 `style`/`source` 入参的工具（或新增 `illustration` / `diagram` 两个工具让模型自选）。推荐**拆分工具**而非塞进一个——模型的意图更清晰、参数校验更简单。Pixabay 作为 **Pexels 的自动回退**（同属"摄影/实拍"类，模型无需感知），在 `SiteImageService` 内部做顺序 fallback。

### 方案 0：Pixabay —— Pexels 的自动回退源（key 已实测可用）

- 目标：**不改变现有交互**（模型/前端都不感知），仅当 `SiteImageService` 走 Pexels 失败或无结果时自动换 Pixabay 同 query 再搜，提升"写实照片"取图成功率。
- 接口（官方文档）：
  - `GET https://pixabay.com/api/?key=KEY&q=…` 返回 `hits[]`；每张含 `previewURL`(150) / `webformatURL`(640) / `largeImageURL`(1280) + `imageWidth/Height`/`tags`/`type`；
  - 参数：`image_type=photo|illustration|vector`、`orientation=horizontal|vertical|all`、`min_width`、`safesearch`、`category`、`colors`、`order=popular|latest`、`page`、`per_page`(3–200，默认 20)、`lang`（含 `zh`）；`fullHDURL/imageURL/vectorURL` 需"full API access"账号，本 key 不保证有；
  - 关键词 ≤100 字符，建议英文（可 `lang=en`）。
- **合规/限制（必须遵守）**：
  1. 限流：**每 60s 最多 100 次，按 API key 计**（非 IP）；响应头 `X-RateLimit-Limit/Remaining/Reset`。→ `PixabayClient` 收到 429 要打日志并读取这些头做退避/告警；本项目量级远低于阈值，够用。
  2. **结果必须缓存 24 小时**、**禁止批量/高频自动查询与系统级批量下载**（"made for real human requests"）。
  3. **禁止永久 hotlink**：Pixabay 图片 URL 只允许临时展示搜索结果，正式使用必须**先下载到服务器**；且 `webformatURL` 标注"valid for 24 hours"。→ 本方案"拿 URL 立即下载 → 传 COS → 页面用 COS URL"正好合规，**绝不把 pixabay 直链写进生成页面**；客户端应下载 `largeImageURL`（webformat 有时效风险）。
  4. **错误体是纯文本**（如 429 `API rate limit exceeded`），不是 JSON → `PixabayClient` 对非 2xx 打日志并按"无结果"回退，**不要**按 JSON 解析（和 Pexels 的错误形态不同，注意区分）。
- 客户端：新增 `core/PixabayClient`，字段对齐现有 `PexelsClient.PexelsImage`（url/width/height/…），带 `orientation`、默认 `image_type=photo&safesearch=true`、`per_page` 设小（如 5）；`SiteImageService` 先 `pexels.searchImages`，无结果/失败（HTTP 非 200 或空）时调 `pixabay.searchImages` 兜底，命中继续走同一套"下载→COS→落库"；返回给模型的文案注明来自 Pixabay（便于排查）。
- 配置：`pixabay.api-key` 放 `application-location.yml`（已 gitignore 不入库）或环境变量 `PIXABAY_API_KEY` 覆盖，与 pexels 一致；**不要**写进仓库。
- 可选后续：Pixabay 另有 `/api/videos/` 视频搜索；本项目当前用图，可留作未来扩展。

### 方案 A：undraw 插画

- **不做**：运行时按关键词调用 undraw 搜索接口（会失效）。
- **做**：
  1. **精选素材库**：一次性人工/半自动挑选 ~40–60 张覆盖常见主题的 undraw 插画（按 slug），下载 PNG 后上传 COS，建成一张 `theme → 主题词 → COS URL` 的**静态索引**（可放一个配置/常量，或后续演进为 DB `app_image` 同款素材表 `site_image_lib`）。
  2. `illustration(query, style)` 工具：query 先做**关键词→主题归类**（简单规则或 AI 已给出主题词），命中索引即返回该主题 COS URL；未命中回退"请换 Pexels 实拍图或改用渐变"。
  3. 后续再想"全量云端搜插画"，可做 **cloudinary 直链按 slug 白名单** 二次扩展，但非首期目标。
- 优点：稳定、可控、无外部接口依赖；能保证插画风格统一。缺点：覆盖面有限（先精选常见主题）。

### 方案 B：Mermaid 架构图（文本 → 图片 → COS）

- 新增工具 `diagram(mermaid, title?)`：模型给出**可直接渲染的 Mermaid 文本**（flowchart/sequence/graph/timeline 等），后端：
  1. 校验/简单 sanitize 文本（长度上限、只允许预期图类型）；
  2. 调 **mmdc**（或 kroki）把文本渲染成 PNG；
  3. 上传 COS（复用 `downloadAndUpload` 同款路径逻辑，取 key `diagram/{appId}/{uuid}.png`）→ 返回 COS URL，模型把它填进 `<img>` / 页面。
- **耗时/并发**：渲染一次约秒级~十几秒（Chromium 启动慢），且**必须串行或限并发**（同一 Chromium 进程并发易崩）→ 走现有 `vueThreadPoolExecutor` 或独立信号量限流；工具回调里同步等待渲染完成再返回（Agent 工具是同步 lambda）。
- **失败降级**：渲染失败/超时返回明确文本，模型改用文字描述或渐变占位（沿用现有兜底哲学）。
- 备选 A/B：
  - **A｜本地 mmdc**：离线可用、无外网依赖，但要处理 Chromium（本机已有 Chrome 时配 `executablePath` 可省一次大下载）。
  - **B｜kroki.io 远程 API**：零本地依赖，但依赖外网，且返回的是 SVG/需二次转 PNG（尺寸/缩放更可控）。
  - → **先 spike A**（本机跑通一条 mmdc 出图），跑不通再退 B。

---

## 三、改造落点（与现有代码融合）

| 文件 | 改动 |
|---|---|
| `core/SiteImageService.java` | Pexels 无结果/失败时**自动回退 Pixabay**（顺序 fallback）；新增 `uploadFileBytes(appId, bytes, ext)`（把 mmdc PNG / undraw 图统一落 COS+落库） |
| `core/PixabayClient.java` | 新增，字段对齐 `PexelsClient.PexelsImage`，`searchImages(query, orientation)` |
| `ai/tools/SearchImageTool.java` | 保持 Pexels（回退在 Service 内部透明处理）；新增 `ai/tools/IllustrationTool.java`、`ai/tools/DiagramTool.java`（各自 `build(appId)`，同工厂模式并入 runToolLoop 的 `List.of(...)`） |
| `core/` 新增 | `IllustrationLibrary`（undraw 精选索引 + 关键词归类）、`MermaidRenderer`（封装 mmdc 进程调用 / 或 kroki） |
| prompt | 三个生成 prompt + `codegen_modify_prompt.txt` 补一段"配图选源"规则（见映射表），让模型判断该用哪个工具 |
| `autoFillImages` | 补图兜底也按场景倾向：若页面是插画风再补图时优先 undraw；架构图类字段跳过 Pexels 直搜 |
| 配置 | `pexels.api-key`/`pixabay.api-key` 都放 `application-location.yml`（不入库）；新增 `codegen.mermaid.command`（mmdc 路径）/ `kroki` 开关 |

---

## 四、实施顺序与工作量

| 阶段 | 内容 | 相对工作量 | 验证 |
|---|---|---|---|
| 0a（spike 首选，低风险） | 接 Pixabay：`PixabayClient` + `SiteImageService` Pexels→Pixabay 回退 + 配置 key；抽 1 张下载验证 | ★ | Pexels 故意失败（如停 key）时仍能取到图并落 COS |
| 0b（spike） | 本机跑通 mmdc 一条 + undraw 一条 cloudinary 直链可达 | ★ | 出 PNG、直链可下载 |
| 1 | `SiteImageService.uploadFileBytes` + `IllustrationTool`(undraw 精选库+归类) + prompt 配图规则 | ★★ | 让模型在插画风页面试用，产物 COS 有插画 |
| 2 | `MermaidRenderer`(mmdc) + `DiagramTool` + 失败降级 | ★★★ | 生成含"系统架构图"的需求 → 页面上有真实渲染的架构图 |
| 3 | 按需强化：illustration 云端全量（cloudinary slug 白名单）、补图按风格路由、素材库表化 | ★★ | 端到端冒烟 |

## 五、风险与注意

1. **undraw 无稳定搜索 API** → 首期用"精选库 + 关键词归类"，不从接口搜（关键风险规避）。
2. **mmdc 依赖 Chromium**：本机首次安装下载大；生产/服务器需确认 Chrome 路径或网络。→ spike 先行；不行退 kroki。
3. **渲染是重量级同步操作**：必须限并发/设超时，避免 Chromium 进程堆积拖垮 worker（参考 Vite/VueBuild 进程管理经验）。
4. **Mermaid 文本可能非法** → 工具先让模型给"可渲染"文本 + 后端 sanitize/试渲染，失败返回给模型重试或降级。
5. **风格映射靠模型判断**，个别会选错源 → prompt 给强映射表 + 允许回退（选错图源不算致命，先保证有图）。

## 六、建议

按"低风险高收益"排序：先做 **0a（Pixabay 回退）**——改动小、立刻提升摄影图取图成功率（Pexels 曾因鉴权/限流静默失败，见 development-notes #66）；再做 **0b spike**（mmdc + undraw 直链）确认可行性；跑通后进 1/2。mmdc 本机装不上就切 kroki 方案重排阶段 2。

