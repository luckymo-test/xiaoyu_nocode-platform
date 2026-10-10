# 计划书：工程结构与功能设计重构

> 日期：2026-10-08
> 目标：把"核心生成链路（AiChat → Facade → VueProjectAgent）"的实现严谨度，补齐到与其设计意图一致；消除功能名不副实、并发竞争、越权散落、零测试等硬伤。
> 现状：后端 Spring Boot 3.5.15 + Spring AI 1.1.8 + MyBatis-Flex；前端 Vue 3 单页 `AppGeneratePage.vue` 已 2100+ 行。本次审计基线为提交 `571d295`。
> 依据：逐文件代码审计（每节均标注证据位置）。

---

## 一、总体判断

不是"整个项目不严谨"，而是**核心生成链路的实现明显低于它的设计意图**，问题集中在一个纵切面：

| 分区 | 严谨度评价 |
|---|---|
| 可观测性（Prometheus/MySQL 分层、指标红线） | **好**——低基数/高基数分离是正确取舍，无需重构 |
| SSE 事件分层、图片链路、COS/Mermaid | **可用**——设计有理由，实现偏弱 |
| AI 生成链路（Facade / VueProjectAgent） | **不严谨**——死代码、假功能、并发无保护、巨型类 |
| 授权校验 | **不严谨**——同一规则散落 3 处，存在越权遗漏风险 |
| 测试 / 数据库迁移 | **缺失**——测试目录存在但 0 个测试文件 |

### 问题分级

| 级别 | 问题 | 影响 | 证据 |
|---|---|---|---|
| **P0** | "停止生成"是假功能 | 用户可见的功能缺陷 | `AiCodeGeneratorFacade` L31 / L210 |
| **P0** | 同一 appId 并发生成无互斥 | 文件互相覆盖、状态互相踩 | `CodeFileWriteService.getProjectDir`；development-notes #63 |
| **P0** | 生成状态机无终态保障 | 进程崩溃后永久卡 `generating` | `updateGenerateStatus` |
| **P0** | 交付前无渲染验证（html/multi_file 开环） | 图裂/布局错/0 图只能靠人发现 | 唯一渲染是 `CoverImageService` 部署后截图，结果不回灌 |
| **P1** | 归属校验散落 3 处 | 越权遗漏风险 | AiChatController×4、ManualEditController×1、AppServiceImpl×1 |
| **P1** ✅ | ~~手动编辑被重新生成整文件覆盖~~ | 已按方案 B 修复（生成前快照 + 生成后幂等重放） | `ManualEditService` vs `writeFile` 覆盖 |
| **P1** ✅ | ~~素材"事后正则补图"而非素材先行~~ | 已按 6.6 改为素材先行（`ImageMaterialPlanner`），`ImageAutoFiller` 已删除 | 见 6.6 的实测结论 |
| **P1** | 核心链路零测试 | 后续重构无安全网 | `src/test` 无文件 |
| **P1** ✅ | ~~无数据库迁移工具~~ | 已引入 Flyway（`V1__init.sql`），空库/已有库两条路径实测通过 | 见 6.4 |
| **P1** | `AiCallListener` 自建线程池 | 违反项目自身规范 #63 | `AiCallListener` L27 |
| **P2** | 巨型类 / 重复代码 / 死代码 / 命名不一致 | 维护成本高 | 见第五章 |
| **P2** | 类注释与实现自相矛盾 | 误导后续维护者 | `VueProjectAgent` 类注释 vs `runToolLoop` |

---

## 二、P0-1：让"停止生成"真正可中断

### 现象与证据

`AiCodeGeneratorFacade` L31 声明了 `GENERATING_TASKS: Map<Long, Disposable>`，但全项目**只有 `remove()`（L210），从来没有 `put()`**。`stopGenerate` 永远取不到句柄 → 空转，却仍把状态改成 `STOPPED`，前端按钮因此"看起来生效、实际无效"。

### 根因

`Disposable` 是 Reactor **订阅**句柄，而生成任务跑在 `vueThreadPoolExecutor.execute(...)`，压根没有 Reactor 订阅。**抽象与实现从一开始就不匹配**，后来实现改了、抽象没删，成了死代码 + 假功能。

### 改法（推荐分两步）

**v1（简单、当天可完成）：取消标志 + 任务注册表**

```java
// core/GenerationTaskRegistry.java（新建）
@Component
public class GenerationTaskRegistry {
    private final Map<Long, GenerationTask> tasks = new ConcurrentHashMap<>();

    /** 抢占式登记：已有任务在跑返回 null（同时解决 P0-2 互斥） */
    public GenerationTask tryAcquire(Long appId) {
        GenerationTask task = new GenerationTask(appId);
        return tasks.putIfAbsent(appId, task) == null ? task : null;
    }

    public void release(Long appId, GenerationTask task) { tasks.remove(appId, task); }

    public boolean cancel(Long appId) {
        GenerationTask t = tasks.get(appId);
        return t != null && t.cancel();
    }
}

// core/GenerationTask.java（新建）
public final class GenerationTask {
    private final Long appId;
    private volatile Thread worker;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void bind(Thread t) { this.worker = t; }
    public void checkCancelled() {
        if (cancelled.get()) throw new GenerationCancelledException(appId);
    }
    public boolean cancel() {
        cancelled.set(true);
        Thread t = worker;
        if (t != null) t.interrupt();
        return true;
    }
}
```

接入点：`WriteFileTool.build(...)` 与 `VueProjectAgent.runToolLoop` 每轮开始前调用 `task.checkCancelled()`（写文件是唯一有副作用的边界，在这里拦最有效）。`stopGenerate` 改为 `registry.cancel(appId)`，捕获 `GenerationCancelledException` 后把状态置 `STOPPED`、SSE 发终态事件。

**v2（可选，追求即时中断）：改用 Reactor 订阅**

把 `streamAndAggregate` 的 `.collectList().block()` 换成 `subscribe(...)`，保留 `Disposable`，取消时 `dispose()` 立断流。代价是聚合逻辑要改成回调式，改动面大，建议 v1 稳定后再评估。

### 验收

1. 生成中点"停止"→ 后续不再有新文件写入（比对目录 mtime）。
2. `app.generateStatus` 变为 `stopped`，SSE 前端正常收尾、不卡在"生成中"。
3. 停止后立即重新生成，可正常开始。

---

## 三、P0-2：生成任务互斥（appId 级）

### 现象与证据

项目目录按 `{codeGenType}_{appId}` 命名（`CodeFileWriteService.getProjectDir`）。用户连点两次生成，两个线程写同一目录，文件互相覆盖、`generateStatus` A 置 DONE 而 B 还在跑。development-notes **#63 自己写了"同一 appId 的 dist/node_modules 并发写要加锁"**，但代码里没实装。

### 改法

复用 P0-1 的 `GenerationTaskRegistry.tryAcquire`：

```java
GenerationTask task = registry.tryAcquire(appId);
ThrowUtils.throwIf(task == null, ErrorCode.OPERATION_ERROR, "该应用正在生成中，请稍候");
try {
    task.bind(Thread.currentThread());
    // ... 原生成逻辑
} finally {
    registry.release(appId, task);
}
```

`AiCodeGeneratorFacade` 的所有 `generate*` / `streamGenerate*` 入口统一走这一层包装。

### 验收

并发发起两次生成：第二次被明确拒绝（提示"正在生成中"），或按业务选择排队；磁盘目录不出现交叉写入。

---

## 四、P0-3：状态机终态保障

### 现象与证据

`AppGenerateStatusEnum` 有 `not_generated/generating/done/stopped/failed`，但 `updateGenerateStatus` 只做一次 UPDATE，无超时回收、无失败原因、无"谁在跑"标记。进程被杀 / OOM，状态永久卡 `generating`。

### 改法

1. **心跳/超时回收**：`app` 表加 `generateStartedAt datetime`，生成开始写入；应用启动时（`ApplicationRunner`）或定时任务，把 `generateStartedAt` 超过阈值（如 15 分钟）的 `generating` 记录重置为 `failed`。
2. **集中状态流转**：抽 `AppGenerationStateMachine`，定义合法流转（如 `generating → done/stopped/failed`，禁止 `done → generating` 之外的越级），所有状态变更只经此一处。
3. **失败原因**：`app` 表加 `failReason varchar(512)`，`catch` 时写入，前端可直接展示。

### 验收

手动把某条记录置 `generating` 且 `generateStartedAt` 设为 20 分钟前，重启应用后该记录被回收为 `failed`。

---

## 五、P0-4：交付前渲染验证

> 详见 [plan-agent-pipeline.md](plan-agent-pipeline.md)——本节是其 P0 项在本方案中的落位。

### 现象与证据

html / multi_file 生成后**没有任何渲染验证**。项目里唯一的浏览器渲染是 `CoverImageService`，它跑在**部署之后**、只为生成封面缩略图，截图结果**从不回灌**给生成链路。vue 的 `VueBuildService.fixBuildError` 修的是**编译错误**，不是"页面渲染是否正确"。

后果：图裂、布局错位、图片 0 张这些问题全部由**人**在浏览器里发现（development-notes #57/#59/#66 即证据），agent 自己无从察觉。

### 改法

复用已有 Selenium 依赖（`CoverImageService` 已用 Chrome/Edge + headless），新增"渲染验证"阶段，接在生成之后、交付之前：

1. 无头浏览器渲染生成的 `index.html`；
2. 3 档视口：桌面 1440 / 平板 768 / 手机 375；
3. 采集结构化问题清单：图裂（`img.naturalWidth===0`）、资源 404、console error、横向溢出（`scrollWidth > clientWidth`）、空容器、每档截图；
4. 把清单喂给修复 agent（把 `fixBuildError` 从"编译错误"泛化为"渲染错误修复"），修完**重新渲染**，最多 N 轮。

### 接入点

`AiCodeGeneratorFacade` 生成流程末尾（`autoFillImages` 之后或替换它）；与 vue 构建失败修复并列，构成统一的"验证 → 修复"回路。

### 验收

1. 故意生成引用不存在图片地址的页面 → 验证阶段报出"图裂"并进入修复。
2. 3 档视口渲染无异常时才置 `done` 并发 `done` 事件。
3. 验证报告可作为阶段产物落盘（`manifest.json`），可追溯。

---

## 六、P1：授权、冲突、测试、迁移

### 6.1 归属校验统一为一处

证据：`AiChatController` L180/L214/L232（"只能修改自己的应用"×2、"只能停止自己的生成任务"）、`ManualEditController.checkOwner` L100、`AppServiceImpl.checkAppOwnership` L574。**同一条规则写了 3 份不同文案**，漏一处即越权。

改法：抽 `AppAuthService`（或扩展 `@AuthCheck` 支持 `ownerOf = "#appId"` 表达式）：

```java
@Component
public class AppAuthService {
    /** 校验登录用户是 appId 的创建者，返回 app；不通过抛 NO_AUTH_ERROR */
    public App requireOwner(Long appId, User loginUser) { ... }
}
```

替换上述 6 处调用。验收：全项目 `rg "只能修改自己的应用"` 只剩 1 处（组件内）。

### 6.2 手动编辑与重新生成的冲突 ✅ 已按方案 B 落地

**证据**：`ManualEditService` 往 `css/styles.css` / `index.html` 注入带 UUID 的 `@user-edit` 标记块，而 AI 重新生成用 `writeFile` **整文件覆盖** → 用户手动改动被抹掉。注意是**部分丢失**——模型只重写 `index.html` 时，`styles.css` 里的样式改动反而活着，所以现象"时丢时活"，更难排查。

**关键前提（决定成本差异）**：标记块写在生成文件里，使得三条交付路径**零改动**即可生效——预览（前端 `buildPreviewDoc` 内联 html/css/js）、下载（zip 工程目录）、部署（`deployApp` 整目录拷贝）。任何"把编辑挪出生成文件"的方案，都必须重新处理这三条路径。

**方案 A：独立 `css/user-edits.css`**

- 机制：样式写独立文件，`index.html` 额外 `<link>` 引用。
- **致命细节**：`index.html` 同样会被重新生成覆盖 → `<link>` 也会被冲掉，所以必须**在写盘时**重新注入 link。若只在"读取/预览时"虚拟注入 link，则**部署目录（裸拷贝）里的 index.html 没有 link → 部署后样式丢失**（"预览正常、部署坏"的隐蔽陷阱）。
- 代价：改 `ManualEditService` + 新增 link 后处理 + `readProjectCode` + 前端 `buildPreviewDoc`；已有应用需一次性迁移标记块。

**方案 B：生成前快照、生成后重放（采纳）**

- 机制：生成前摘出工程里的标记块（`snapshot`），生成后写回（`replay`）。
- **必须幂等**：先清掉目标文件里已有的标记块再追加——模型可能只重写了部分文件，未重写文件里块还在，直接追加会产生重复。
- **必须放 `finally`**：生成失败或被手动停止（P0-1）时，用户改动同样不能丢。
- 交付路径**零改动**（块仍在生成文件里）。

| 维度 | 方案 A | 方案 B ✅ |
|---|---|---|
| 根治性 | 高（用户数据与 AI 产物分离） | 中（仍混在同一文件，但不会再丢） |
| 改动面 | `ManualEditService` + link 后处理 + `readProjectCode` + 前端预览 | 生成入口快照/重放 + 复用块解析 |
| 交付路径 | 预览/下载/部署**都要动**（漏一个就丢样式） | **零改动** |
| 迁移 | 需迁移已有标记块 | 无 |
| 主要风险 | link 丢失（隐蔽，部署才暴露） | 选择器漂移：重写后类名变了，重放规则可能静默不生效 |

**已知局限（方案 B）**：选择器漂移导致重放规则失效（不报错）；文本覆盖依赖 `data-sel` 选择器，重写后可能找不到元素。缓解：用户手动修改已写入 `.agent/spec.md`（M2），模型重新生成时能看到"这些改动要保留"。

**后续演进方向**：把手动修改搬到**独立叠加层**（`.agent/user-edits` + 交付时合并），彻底分离用户数据与 AI 产物——届时可复用本方案已抽出的标记块解析工具，不必返工。

**验收（已实测通过）**：手动文字覆盖 → AI 重新生成（16.8s，`index.html` 必被重写）→ `@user-edit` 块**仍在且只有 1 个**（未丢失、未重复），探针文本仍在，标记块位于 `</body>` 之前。另有 4 个单测覆盖快照/重放/幂等/空快照。

### 6.3 补最小测试集

现状：`src/test/java/com/mofanyi/{ai,controller}` 目录存在，**0 个测试文件**。对一个"生成→构建→部署"长链路且依赖外部模型的项目，这是最大缺口。

优先补（不依赖真实模型，mock `ChatModel`）：

| 测试对象 | 覆盖点 |
|---|---|
| `ToolPaths` | 路径穿越 `../../` 必须被拒（安全） |
| `VueProjectAgent` | prompt 选择、结果组装、usage 累加 |
| `GenerationTaskRegistry` | 互斥、取消语义 |
| `AppAuthService` | 越权用例（非本人 → NO_AUTH_ERROR） |
| `ManualEditService` | 标记块注入 / 撤销 / 清空 |

surefire 已在 `spring-boot-starter-test` 中，无需新增依赖。

### 6.4 数据库迁移工具

现状：`sql/` 是散落的手工 `ALTER TABLE`，无版本、无回滚。

改法：引入 **Flyway**（Spring Boot 内置支持），把现有 `sql/*.sql` 收敛为 `V1__init.sql` 等带版本脚本，`baseline-on-migrate=true` 兼容已有库。

> **已完成（2026-10-10，4 步）**：`sql/*.sql`（4 个幂等建表脚本）已收敛为
> [V1__init.sql](src/main/resources/db/migration/V1__init.sql)，`sql/` 目录删除；`pom.xml` 加 `flyway-core` +
> `flyway-mysql`；`application.yml` 配 `baseline-on-migrate=true` + `baseline-version=0`（**刻意取 0**：
> 已有库基线在 0，V1 仍会执行一次，靠脚本的 `if not exists` 幂等收尾——若取默认 1，V1 会被跳过，
> 新库反而少建表）。
>
> 两条路径都用**真实启动**验过：
> - **空库**：新建 `xiaoyu_migrate_test`（0 张表）→ 启动 → `Migrating schema ... to version "1 - init"` →
>   `Successfully applied 1 migration ... now at version v1` → 库里 5 张业务表 + `flyway_schema_history` 齐全。
> - **已有库**：直接启动主后端 → `Successfully baselined schema with version: 0` → V1 幂等执行 → 应用正常启动、
>   数据无损（登录与查库实测正常）。

### 6.5 线程池归 Spring 管理

证据：`AiCallListener` L27 自建 `Executors.newVirtualThreadPerTaskExecutor()`，违反项目规范 #63"线程池归 Spring 管理"。

改法：定义受管的 `ExecutorService` Bean（虚拟线程执行器），`AiCallListener` 构造注入。

---

### 6.6 素材先行（替代事后正则补图）

> 详见 [plan-agent-pipeline.md](plan-agent-pipeline.md)——素材先行的完整论证与方法论对照。

> **已完成（2026-10-10，10 步）**：新增 `ImageMaterialPlanner`（多 query 候选池 → 去重 + 最小分辨率闸门 +
> 宽高比分桶 top-K → 上传 COS），在 `runToolLoop` **之前**把清单作为 system 消息注入；`searchImage` 在有清单时
> 直接返回清单（保证页面 URL 落在清单内）；`ImageAutoFiller` 及 `SiteImageService.fetchOneImage` 已删除。
> 实测（新 app、图片主导 prompt）：`[素材] 预取完成：候选 12 张（分辨率淘汰 0），入选 8 张` 出现在 `round=1` 之前；
> 模型**未调用 searchImage**（工具序列 `writeFile×5 → finish`），直接用了清单——页面 `js/app.js` 里 8 幅作品的 src
> **全部**来自清单（逐 UUID 比对）；渲染验证 → 异步修复 → **复验 3 档视口全部通过**。
> **英文 query 化（2026-10-10 追加，部分兑现"候选池扩容"）**：实测发现**多 query 的第二条（`xx background`）恒为空**
> —— 3 次运行候选都恰好 12 张（= 单 query 上限），池子一直是半个。改为"中文 query + 模型扩写的 2 个英文 query"：
> 候选池 **12 → 36**（`query：中文可用 12 张；英文扩写 [hanfu woman traditional chinese costume portrait,
> ancient chinese style photography elegant hanfu garden]（候选池增至 36 张）`），冷门中文词（汉服）也能拿到准的英文检索词。
> 先做的是"命中不足才扩写"，但实测中文 query 已够 8 张 → 从不触发，等于白写，故改成**每次都追加**（代价是一次小模型调用）。
> **仍未做（11~13 步）**：候选池进一步扩容（加大 per-query 上限/翻页）、更深的质量闸门（感知去重/水印/视觉打分）、
> 素材加工（裁切/压缩/命名）。
>
> **图源 SPI（2026-10-10，为"加更多图源"铺路）**：新增 [`ImageSource`](src/main/java/com/mofanyi/core/ImageSource.java)
> 接口（`name()` / `search()` / `allowedHostSuffixes()` + 统一 `ImageCandidate`），Pexels 与 Pixabay 各实现之；
> `SiteImageService` 与 `ImageMaterialPlanner` 的**两份硬编码回退编排合并为一处**（按 `@Order` 依次问源），
> `ToolRiskRegistry` 的**出站白名单改为从各图源声明汇总**——消灭了"加了图源忘了加白名单"这类漏接
> （现象是"接口能搜到、下载全失败"）。新增图源现在是"写一个实现类"，编排与白名单自动生效。
> 新增可观测：`[素材] query=… 命中源=pexels 候选=12 张`，能直接看出每个 query 落到哪个源。
> 验证：38 用例通过；同一 prompt 真实生成行为不变（候选 48 张 / 入选 8 张 / 复验 3 档通过）。
>
> **新源可行性（实测数据见 [plan-image-sources.md](plan-image-sources.md) 顶部）**：Openverse 与 Wikimedia
> **国内网络不可达**（CONN-FAIL），原计划作废；Unsplash 可达但条款要求热链+署名，与"转存 COS"冲突，待决策；
> 四家国内文生图服务（硅基流动 / 阿里百炼 / 火山方舟 / 智谱）**全部可达**——而 `spring-ai-openai` 已自带
> `OpenAiImageModel`，接 OpenAI 兼容端点**零新依赖**。故下一步新源 = **生成式兜底**（待确定供应商与 key）。
> 顺带发现两条待办：① `ProjectCodeScanner` 的 URL 正则**看不懂模板字符串拼接**（`${CDN}/x.jpg`），
> 本次只扫到 2 个 URL（清单 8 张已由 planner 单独落库，故数据不缺，但扫描口径不准）；
> ② `RenderVerifier` 会把**无 src 属性的 JS 托管 img**（灯箱占位）判成"空 src" → 多走一轮修复。

证据：`VueProjectAgent.generate` 先 `runToolLoop`（写页面）、再 `autoFillImages`（正则补图）。页面是在不知道素材的情况下写出来的，所以会出现 `image: ""` 占位，补图只能靠正则猜。候选池仅 `pageSize=5`（`PexelsClient`），无筛选闸门；`downloadAndUpload` 原样上传原图（`SiteImageService`）。

改法：

1. 需要图片时**先生成素材清单**：多 query 扩大候选池（5 → 20~30），加质量闸门（最小分辨率、宽高比分桶、感知去重、水印启发式、可选视觉模型打分），选出 top-K。
2. 把清单（url / 宽高 / 用途）作为事实注入**页面构建** prompt，页面围绕真实素材设计。
3. **删除 `autoFillImages` 正则补图**（它优化的是"不为空"而非"好"）。
4. 素材加工：按目标宽高比裁切、压到 web 尺寸、统一命名（现为原图直传、后缀硬编码 `.jpg`）。

验收：生成一个图片主导页面，页面中图片 URL 全部来自预选清单；`autoFillImages` 相关代码与正则被移除。

---

## 七、P2：结构瘦身与一致性

### 7.1 拆巨型类

**执行顺序（务必分步：每步独立编译 + 真实生成验证，勿一次性大改）**

`VueProjectAgent`（~780 行）按职责拆为 5 个类。下表的"需要改的调用点"是**已核过的实际引用关系**（这一步才是本重构的难点，不是搬代码本身）：

| 步 | 抽出物 | 依赖 | 需要改的调用点（VueProjectAgent 内） |
|---|---|---|---|
| **S1** | `ProjectCodeScanner` | `CodeFileWriteService`、`SiteImageService` | `generate`（`scanAndSaveImages`）、`autoFillImages`（`collectCodeFiles`/`relativePath`/`countCosUrls`）、`buildFileIndex` |
| **S2** | `ImageAutoFiller` | `CodeFileWriteService`、`SiteImageService`、**上一步的 `ProjectCodeScanner`** | `generate`（`autoFillImages`） |
| **S3** | `TokenUsageAccumulator` | 无 | `generate`（usage 数组）、`runToolLoop`（accumulateUsage）、`GenerateResult.setUsage` |
| **S4** | `CodeGenPromptRegistry` | `CodeGenPromptConfig` 的 5 个 prompt Bean | `promptForType`、`vueFixPrompt`/`modifyPrompt`/`renderFixPrompt` 的使用点 |
| **S5** | `AgentToolLoop` | 5 个工具 Bean + `GenerationTaskRegistry` + 上一步的 accumulator | `runToolLoop`、`streamAndAggregate`；**顺带把循环改成自管**，让逐步预算真正生效（现阶段预算只在模型调用返回后检查，见 engineering 第五章） |

**每步的验证门槛（缺一不可）**：

1. `mvn -o test`（当前 37 个用例）全绿；
2. 重启后端，跑**一次真实生成**（`GET /api/ai/chat?appId=&message=`），确认产出文件与改动前一致；
3. 若涉及工具链路，再验一次「停止生成」与「渲染验证」（这两条单测覆盖不到）。

**为什么必须分步**：`autoFillImages` / `countCosUrls` / `buildFileIndex` 三者**共用** `collectCodeFiles` 与 `relativePath`。若一次性把方法搬走，会同时牵动 `generate` 主链路的三处调用；一旦漏改就是编译错，改错就是生成行为变化——而**生成行为只能靠真实生成验证**（单测覆盖不到工具循环，这正是此前"异步修复抢不到锁"能溜过去的原因）。

**S1 的推荐做法**（最小可验证切片）：

1. 新建 `core/ProjectCodeScanner`，承接 `collectCodeFiles` / `relativePath` / `scanAndSaveImages` / `countCosUrls`；
2. `VueProjectAgent` 的 `autoFillImages` 与 `buildFileIndex` 改为**调用该组件的公开方法**（本步先不搬 `autoFillImages` 自身）；
3. 编译 + 单测 + 真实生成验证通过后，再进入 S2。

**`VueProjectAgent`（722 行）** 按职责拆为：

| 抽出物 | 职责 |
|---|---|
| `CodeGenPromptRegistry` | 按类型选 prompt（现 `promptForType`） |
| `ImageAutoFiller` | `autoFillImages` / `fillImagesInContent` |
| `ProjectCodeScanner` | `scanAndSaveImages` / `collectCodeFiles` / `relativePath` |
| `TokenUsageAccumulator` | `accumulateUsage` / `TokenUsage` |
| `AgentToolLoop` | `runToolLoop` / `streamAndAggregate` |

**`AppServiceImpl`（576 行）** 按职责拆为：`AppDeployService`（含 `doDeploy`）、`AppFeaturedService`（精选审核）、`AppQueryService`（分页/QueryWrapper）、`AppConverter`（VO 转换）、`AppRecycleService`（回收站/物理删除）。

#### S5 验收标准（自管循环）—— 本步是"行为变更"，不能沿用前四步的判据

**S5-3 逐条验收（开关 true 路径）**

| # | 标准 | 结论 | 证据 / 缺口 |
|---|---|---|---|
| ① | 可回滚开关 | ✅ **通过** | true 路径见下方 S5-2/⑤ 证据；**false 路径已实测通过**（2026-10-10，`CODEGEN_SELF_MANAGED_LOOP` 未设，即默认 false），详见下方「① 的 false 路径证据」 |
| ② | 预算真正生效 | ✅ **通过** | 上限临时调为 20000 后实测被拦下，详见下方时序证据 |
| ③ | 上下文增长 | 🟡 **打点已拆列并首测；比值超初值 K=3** | 本轮 prompt 已单列，比值可算。实测（模式 self，2026-10-10）：6 轮收敛 `61408/10604 = 5.79`；8 轮撞预算 `40913/10888 = 3.76` —— **均 > 初值 K=3，K 需重定**。同口径对照（同 app 同 prompt）总量降到框架路径 **46%**，详见下方「③ 分列打点实测」 |
| ④ | 能力不退化 | ✅ **通过** | `agent_progress` ✅ 实测推送；`reasoning` ✅ **已恢复**（2026-10-10）：自管循环由非流式 `call()` 改回流式（方案 a 的简化版），实测 SSE `event:reasoning` **9792 条**、`agent_progress`×5、`done`×1、`error`×0，全链路 `[verify] 3 档视口全部通过`。原"方案 b 的已知回退"已消除 |
| ⑤ | 四条回归 | ✅ **通过** | 生成 / 可视化修改 / 停止生成 / 渲染验证 全跑，见下方证据 |
| ⑥ | 性能 | 🟢 **通过（边缘）** | 78.8s vs 基线 58~75s，+5%~+36%，**上界略超 ±30%** |

**③ 的实测价值（口径已修正）**：自管循环**首次**产出逐轮用量，这一点仍然成立。但原先引用的总量 `138975`
其实是**轮次开始前的累计值**（只累计到第 4 轮，漏掉末轮自身用量），且拿 total 去比框架路径的 **prompt** 基线，
口径不齐。本次拆列后按**同一 app、同一 prompt** 直接对照：自管循环 total `214603` vs 框架路径 `463557`，
**约 46%**（prompt 口径 45%）。详见下方「③ 分列打点实测」。

**② 的时序证据（关键：拦在"轮次之间"）**

上限临时调为 20000 时的实测：

```
round=1 模式=self 累计token=0/20000        已耗时=1ms
round=2 模式=self 累计token=10573/20000    已耗时=1383ms
BusinessException: 生成超出 token 预算（已 41966，上限 20000），已终止
HTTP 200 -> {"code":50001,"message":"生成超出 token 预算（已 41966，上限 20000），已终止"}
```

终止发生在 **round=2 的调用之后、round=3 的开始之前** —— 检查确实在"轮次之间"执行，而非整轮烧完才检查。
总量 `41966 = 上限 20000 + 单轮消耗`，**符合验收标准 ② 事先写下的边界** `totalTokens ≤ 上限 + 单轮最大增量`。
耗时 10.3s（提前终止）。**这是框架自管循环做不到的**——它的内部轮次对外不可观测（见上文"数 hasToolCalls 块数"的证伪）。

> **第二次独立复现（2026-10-10，默认上限 200000，portfolio prompt）**：`round=8 … 累计token=168341/200000`
> → 第 8 轮单轮用掉 `46262` → 第 9 轮开始前 `生成超出 token 预算（已 214603，上限 200000），已终止`。
> 同样满足 `totalTokens ≤ 上限 + 单轮最大增量`（`214603 ≤ 200000 + 46262`），拦截点同样在轮次之间。

**③ 分列打点实测（2026-10-10，模式 self，同一 app）**

打点拆列后的逐轮 `本轮prompt`（该轮实际发给模型的 prompt）：

| 轮次 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 合计 prompt | total（prompt+completion） |
|---|---|---|---|---|---|---|---|---|---|---|
| A：复杂业务落地页（6 轮收敛） | 10604 | 14442 | 26046 | 38448 | 44428 | 61408 | — | — | 195376 | 250347 |
| B：portfolio（8 轮后撞预算） | 10888 | 13135 | 14796 | 15952 | 22592 | 30307 | 34282 | 40913 | 182865 | 214603 |

对应逐轮 `本轮completion`：A = `2779 / 11581 / 12378 / 5956 / 16956 / 5321`（合计 54971）；
B = `839 / 234 / 450 / 6617 / 7691 / 3951 / 6607 / 5349`（合计 31738）。两组的 `prompt + completion`
都与轮次开始前打印的 `累计token` 对得上（例：B 第 8 轮开始前 `累计token=168341` = 前 7 轮 prompt+completion 之和）。

- **比值**（③ 判据）：A `61408 ÷ 10604 = 5.79`；B `40913 ÷ 10888 = 3.76` —— 均超初值 K=3。
- **同口径对照**（同 app、同 prompt，只切开关）：框架路径 `prompt 406371 / completion 57186 / total 463557`（跑完）；
  自管循环 `prompt 182865 / completion 31738 / total 214603` → **total 46.3%、prompt 45.0%**。
  **注意这是下界**：自管那次是撞预算被掐断的（没跑完），跑完只会更高；两次运行之间 app 的 spec/记忆也有小幅增长，
  严格对照需要固定上下文重测。尽管如此，量级结论成立——**这是"自管循环值得存在"的直接量化证据**
  （此前那组 `138975` 比 `253075~501563` 的口径不成立，见上文修正）。
- **运维注意（已处置）**：B 撞上的是**默认上限** —— 同一 prompt 在框架路径下能生成完（烧到 463557），自管循环在
  `max-loop-tokens=200000` 下会被判超预算。**默认值已由 20 万上调到 80 万**，并在 `application.yml` 里接线
  （`CODEGEN_MAX_LOOP_TOKENS` 可覆盖），见下方「token 上限调整」。

**⑤ 四条回归的证据**

| 回归 | 证据 |
|---|---|
| 生成 | HTTP 200；`[scan]` 21 个图片 URL；`[verify]` 3 档视口全部通过 |
| 可视化修改 | SSE 事件 = `agent_progress` + `done`；走自管循环 `round=1→2→3 模式=self`；17.9s |
| 停止生成 | `[task] 已请求停止生成` → `GenerationCancelledException: 生成已被手动停止` |
| 渲染验证 | `[verify] 渲染验证完成：3 档视口全部通过`（自管模式下的完整生成） |

**取消粒度变细（改进，非仅不退化）**：框架路径只能在整个生成返回后中断（实测 23.6s）；自管路径每轮开始检查，**一轮内即可中断**。

**① 的 false 路径证据（开关关闭 = 框架内部循环，2026-10-10 00:34–00:41）**

重启时**不带** `CODEGEN_SELF_MANAGED_LOOP`（走默认 false），同一应用 / `html` 类型，三条回归实测：

| 回归 | 证据 |
|---|---|
| 生成 | HTTP 200；日志 `[agent] round=1 模式=framework 累计token=0/200000` + `[agent] tool-loop 结束：外层 step=1，写文件数=3` —— 与 S5-1 之前的形态逐字一致（单次 `stream()` 内框架自跑完全部工具调用）；`[scan] 扫描到 19 个图片 URL` |
| 渲染验证 | `[verify] 3/3 档视口存在问题，转异步修复`（`verify-before.json`：三档各 1 处图裂）→ 异步修复 → `[verify] 异步修复完成，复验：3 档视口全部通过`（`verify-after.json`：三档 broken=0 / overflow=false） |
| 停止生成 | `[task] 已请求停止生成：appId=… worker=http-nio-8124-exec-4` → `GenerationCancelledException: 生成已被手动停止`；HTTP `{"code":50001,"message":"生成已被手动停止"}`；`GET /api/app/get/vo` 返回 `generateStatus=stopped`。停止调用到生成结束 **98.4s ≈ 一次模型调用时长**，与"框架路径只能在整个生成返回后中断"一致（true 路径为一轮内即可中断） |

**结论**：开关确实可回滚——false 路径的循环形态、落盘结果、渲染验证与取消语义均与改动前一致；true 路径才是行为变更。① 的判据（"false 时行为与改动前一致；true 时走新路径"）两条都满足。

> 附注：本轮模型时延偏高（单次流式调用 238s），与开关无关——两条路径执行的是同一次模型调用，差异只在循环由谁驱动；⑥ 的性能对照以基线同口径计时，不受此影响。

**剩余待验**：① ④ 均已完成；只剩 ③ 的 **K 初值需要重定**（是判据口径问题，不是实现缺口——实测 3.76 / 5.79，
`K=3` 是当初拍的数）。

**新发现的缺口（本次打点拆列照出来的，未修）**：**修复类流程不走预算**。`fixBuildError` 与 `fixRenderIssues`
调用 `runToolLoop` 时传的是 `usage = null`（`VueProjectAgent` L266 / L300），于是这些流程里 ② 的打点恒为
`prompt=0 completion=0`，`checkBudget` 拿到 0 —— **token 预算对它们完全不生效**（只剩墙钟闸门）。
本次异步渲染修复的三轮实测日志即是证据：`本轮prompt=0 本轮completion=0`。
`generate`（L207）与 `modifyGeneratedSite`（L340）传的是真累加器，不受影响。修法很小（让门面为修复流程也建一个
累加器），但属独立一步，待排期。

**运维注意**：`codegen.agent.self-managed-loop.enabled` 默认 `false`（走框架循环）；`codegen.agent.max-loop-tokens`
默认 **800000**（2026-10-10 由 200000 上调），两者都可用环境变量覆盖（`CODEGEN_SELF_MANAGED_LOOP` /
`CODEGEN_MAX_LOOP_TOKENS`）。

**token 上限调整（2026-10-10）**

- 触发：同一 portfolio prompt 在框架路径单次调用烧到 `463557`，自管循环在 20 万上限下跑到第 9 轮被掐断
  （`已 214603，上限 200000`）——**上限比一次正常生成的实际开销还低**，等于把大页面全判死。
- 取值：**800000**（≈ 最重实测值的 1.7 倍）。定位仍是"拦住跑飞"，主力闸门是 `MAX_STEPS=20` 与墙钟 600s。
- 接线：新增 `codegen.agent.max-loop-tokens: ${CODEGEN_MAX_LOOP_TOKENS:800000}`，与 `self-managed-loop` 同形；
  代码里的 `@Value` 兜底同步改为 800000。
- 实测验证（自管循环，同一 prompt）：**HTTP 200，5 轮收敛，总耗时 122.8s，`prompt 155020 / completion 17867 /
  total 172887`**（日志 `累计token=…/800000`），随后 `[scan] 20 个图片 URL` → `[verify] 3/3 档视口存在问题` →
  异步修复 → **复验 3 档视口全部通过**。全链路未被预算打断。

**S5-2 前置：API 调研结论（已用 `javap` 核对本地 1.1.8 jar，全部确认）**

| 用途 | 确认的签名 |
|---|---|
| 关闭框架内部工具执行 | `DefaultToolCallingChatOptions.builder().toolCallbacks(List<ToolCallback>).internalToolExecutionEnabled(Boolean).build()` |
| 取模型的工具调用 | `AssistantMessage.getToolCalls()` → `List<AssistantMessage.ToolCall>`；`hasToolCalls()` |
| ToolCall 结构 | record `ToolCall(String id, String type, String name, String arguments)` → 访问器 `id()/type()/name()/arguments()` |
| 执行工具回调 | `ToolCallback.call(String toolInput)` → `String`；`getToolDefinition().name()` 取工具名 |
| 成对写回历史 | `ToolResponseMessage.builder().responses(List<ToolResponse>).build()`；`ToolResponse(String id, String name, String responseData)` |
| 重建 assistant 消息 | `AssistantMessage.builder().content(String).toolCalls(List<ToolCall>).build()` |

**调研中发现的两个连带问题（动手前必须先解决）**

1. **现有 `streamAndAggregate` 会丢掉 toolCalls**：它返回的是
   `AssistantMessage.builder().content(text).build()` —— **没有 toolCalls**。
   自管循环要把模型的 assistant 消息入历史，若沿用这个对象，历史里就没有 `tool_calls`，
   模型会以为"我没调过工具"，下一轮必然跑偏。**必须让它把 toolCalls 一并聚合返回。**
2. **流式下的 tool-call 参数是分片的**：`id`/`name` 通常只在首个 chunk 出现，`arguments`
   会跨多个 chunk 被切开。要正确重组，必须**按 tool-call 序号累加参数片段**——
   这正是框架内部替我们做的事，自己实现等于重写它的累加器，且**拼错就是非法 JSON、工具执行失败**。

> 由此引出一个**方案选择**（S5-2 开工前要定）：
> **(a)** 流式 + 自己累加 tool-call 片段（保住 `reasoning` 透传，但实现复杂、易错）；
> **(b)** 每轮改用非流式 `call()`（tool_calls 天然完整，实现简单），代价是 **`reasoning` 透传退化**——
> 而这违反验收标准 ④（不得退化），所以需要先决定 ④ 是否可暂时豁免；
> **(c)** 混合：仅在"有工具调用的轮次"用非流式，纯文本轮次保持流式。折中，但状态判断要小心。

**方案 a 的前提探针（2026-10-10 实测，结论：前提成立，且比预想简单得多）**

探针代码：[SelfManagedStreamProbeTest](src/test/java/com/mofanyi/ai/SelfManagedStreamProbeTest.java)（默认不参与常规套件，
`mvn -o test -Dtest=SelfManagedStreamProbeTest -DcodegenProbe=true` 显式开启；每次会真实调用模型 4 次）。
做法：拿真实的 `openAiChatModel`，同一个强制调工具的小任务，只切 `internalToolExecutionEnabled`，逐 chunk 记录
「tool_calls 是否出现 / id、name、arguments 是首片还是分片 / 能否拼回合法 JSON / reasoning 增量 / usage 出现位置」。

| 场景 | chunks | **含 toolCalls 的 chunk** | id/name/args 片段 | args 字符 | 拼回合法 JSON | 带 reasoning 的片段 | 带**非零** usage 的 chunk |
|---|---|---|---|---|---|---|---|
| OFF / 短载荷 | 33 | **1** | 1/1/1 | 48 | ✅ | 33/33 | **1**（就是那一片） |
| OFF / 长载荷 1500 字 | 157 | **1** | 1/1/1 | 2230 | ✅ | 157/157 | **1** |
| OFF / 长载荷 6000 字 | 175 | **1** | 1/1/1 | **11100** | ✅ | 175/175 | **1** |
| ON / 短载荷 | 52 | **0** | 0/0/0 | 0 | — | 52/52 | 23 |

四条结论，逐条对应原来的担心：

1. **`internalToolExecutionEnabled=false` 时 tool_calls 会下发到 `doOnNext`**，而且 **`id`/`name`/`arguments` 都在同一个
   chunk 里且已是完整 JSON** —— 连 **11,100 字符**的参数都不分片。也就是说，**上面「连带问题 2」的"分片累加器"不需要写**：
   方案 a 的实现退化成"用 `stream()`、直接取那个带 toolCalls 的 chunk"。前提不是"勉强成立"，是"比方案 b 还简单"。
   （机制上说得通：`spring-ai-model` 里有 `MessageAggregator`，聚合发生在框架内部；换 provider 时应重跑本探针。）
2. **reasoning 在 OFF 流里全程透传**（33/33、157/157、175/175 都带增量）→ 验收 ④ 的 `reasoning` 回退**可以恢复**，
   代价远低于当初"重写累加器、易错"的估计。
3. **ON 模式全程 0 个 toolCalls chunk** → 复现并解释了此前"数 `hasToolCalls()` 块数恒为 0"的证伪：工具调用增量被框架
   消费去执行工具了，不下发。framework 路径的不可观测性由此定性。
4. **非零 usage 只出现在最后一个 chunk**（OFF 时它就是带 toolCalls 的那一片，`finish_reason=tool_calls`）→ 自管循环
   若改走流式，取 usage 的落点只有一个：流末尾那片。中途 3,300+ 片全是 `0/0`。

> **探针的落地（2026-10-10，已完成）**：据此把自管循环从非流式 `call()` 改回流式，采用的是 **(a) 的简化版**
> ——因前提 1 成立，**没有写任何分片累加器**，改动只有两处：`streamAndAggregate` 顺带聚合 tool_calls（原先它是
> 丢 toolCalls 的，即"连带问题 1"），自管循环改调它并接上 reasoning sink。
> 验收 ④ 由此从"一半通过"变**通过**：SSE 实测 `event:reasoning` **9792 条**（`agent_progress`×5、`done`×1、
> `error`×0），同一轮真实生成 7 轮自管收敛、`[verify] 3 档视口全部通过`。

前四步的判据是"日志与产出跟改动前一致"；而 S5 的目的是**改变**生成过程（让逐步预算真正生效）。
用它自己的话讲：**"成功"和"回归"在观测上必须能分开**，所以另立标准。

**① 可回滚（最重要）**

- 自管循环做成开关：`codegen.agent.self-managed-loop.enabled`，**默认 false**（新旧路径并行存在，不是替换）
- 判据：开关 false 时行为与改动前一致；true 时走新路径
- 理由：行为变更若不能一键退回，一旦出问题只能回滚整个提交——把风险从"整次重构"缩小到"一个开关"

**② 预算真正生效（S5 存在的唯一理由）**

- 新增可观测（两行配合，缺一不可）：
  - 每轮<b>开始前</b>：`[agent] round=N 模式=X prompt=a completion=b 累计token=C/上限D 已耗时=Zs`
  - 每轮<b>返回后</b>：`[agent] round=N 本轮prompt=a 本轮completion=b total=c 调用耗时=tms`
  - 为什么必须两行：开始前那行记的是"进入本轮时"的累计，**收敛结束的循环不会再打印下一轮的累计行**，
    末轮自身的用量只存在于返回后那行里 —— ③ 的"末轮 promptTokens"只能从它取
- 判据（可证伪）：构造超预算场景，确认**在轮次之间被拦下**，且
  `totalTokens ≤ 上限 + 单轮最大增量`（而不是现状的"整轮烧完才检查"）
- **现状反证数据**：三次实测 `218313` / `276833` / `288824`，均 > 20w 上限却未拦下

**③ 上下文增长被压住（附加收益）**

- 判据：同一复杂 prompt，**末轮 promptTokens ÷ 首轮 promptTokens ≤ K**（K 需实测标定，初值 3）
- **实测结果（2026-10-10，模式 self）**：6 轮收敛 **5.79**、8 轮撞预算 **3.76** —— 均 > 初值 K=3。
  即**判据本身需要重定**，而不是"已达标"：自管循环并不压缩历史（每轮把 assistant 的 `tool_calls` 与工具结果
  **原样追加**），比值随轮数近似线性增长。
- **"压住"这件事已经试过了（2026-10-10，反效果）**：按 plan-agent-engineering 第三章实现 L1（旧工具参数/结果换成
  占位），**控制变量实验**（两个全新 app + 同一 prompt + 只切开关）显示：轮数 8 → **20**、总 prompt 163381 →
  **191470**（不降反升）、total 基本持平、渲染验证 3/3 通过 → 1/3 有问题。机制是"丢失即重读"：模型改去 `readFile`
  把文件读回来（该轮 17 次），读回来的结果和原内容一样大。故 L1 **默认关闭**（`CODEGEN_CONTEXT_SLIM=false`），
  结论与后续三条改进方向见 plan-agent-engineering 第三章末。**结论绑定 provider**（deepseek-v4-flash）。
- **基线在当前形态下测不到（重要发现）**：框架在一次 `stream()` 内自跑完工具循环，我们只看到"一次调用"——
  日志里 `外层 step=1` 恒成立、也没有分轮打点，**每轮 promptTokens 在 S5 之前根本无法获得**。
  所以 ③ 的比值基线只能等 S5 落地后才有；S5 之前只能采**间接基线**（见下表）。
- 可行的间接基线（现在就有）：单次生成的**总 promptTokens**、总耗时、写文件数
- ~~数 `hasToolCalls()` 块数即可拿到内部轮数~~ —— **已实测证伪**：该计数**恒为 0**，即使同一次生成
  确实写了 3 个文件。原因是框架在 `internalToolExecutionEnabled=true` 下**自行消费工具调用增量去执行工具**，
  不把这些 chunk 继续下发到 `doOnNext`。**外部没有任何手段观测内部轮数**，该打点已回退。
  结论：S5 的"分轮"相关数据（轮数、每轮 promptTokens）**只能等自管循环落地后才能采集**。

**S5 改造前基线（真实实测，同一应用 / html 类型 / 相近 prompt）**

| 指标 | 实测值 | 与预算或标准的关系 |
|---|---|---|
| 总 promptTokens | 253075 / 254841 / 271306 / **501563** | **均超 20w 预算**；最后一个来自同一应用的后续一次生成 —— **上下文翻倍**，佐证 S5 必要性 |
| 总 totalTokens | 218313 / 276833 / 273141 / 288824 | 超预算 **9% ~ 44%**，且**全部未被拦下** |
| 总耗时 | 58454 / 64087 / 69915 / 74640 ms | ⑥ 的 ±30% 以此为对照 |
| 写文件数 | 3（偶为 1 或 4） | ④ 产物对比基准 |
| 外层 step | **恒为 1**（仅异常重试时为 3） | 证明外层循环从不迭代，全部轮次由框架内部完成 |
| 分轮 promptTokens / 内部轮数 | **无数据（已证伪无法采集）** | 框架吞掉工具调用 chunk，外部不可观测；只能等 S5 |

**④ 现有能力不退化**

- `reasoning` 透传（SSE `reasoning` 事件）仍在
- 逐文件进度（SSE `agent_progress`）仍在，且文件名/内容正确
- `[spec] 注入` / `[scan]` / `[verify]` 链路不变
- token 三数对账仍成立（`prompt + completion == total`）

**⑤ 四条回归**

生成 / 可视化修改 / **停止生成（仍能在一次模型调用内中断）** / 渲染验证

**⑥ 性能与质量**

- 总耗时与改动前同量级（±30%）
- 同 prompt 产物：文件数、图片数、渲染验证结果不劣于改动前

**必须接管的框架职责（提前列明，避免漏项）**

| # | 职责 | 备注 |
|---|---|---|
| 1 | 解析模型的 `tool_calls`（名字 + 参数 JSON） | |
| 2 | 执行工具回调（writeFile / readFile / searchImage / diagram / finish） | 回调里不能抛异常终止生成（见 development-notes #68） |
| 3 | 把 assistant 的 tool_calls 与工具结果成对写回历史 | **风险最高**：顺序/配对错会导致模型困惑或 API 报错 |
| 4 | 终止条件（无 tool_calls，或 finish 已调用） | 对应现在的 `MAX_STEPS` 与 `finished` |
| 5 | 工具异常处理**必须与现状一致** | 吞成工具错误结果喂回模型，不能改成向上抛 |
| 6 | 关闭框架内部工具执行 | `internalToolExecutionEnabled=false` |

> **风险最高的是第 3 条**（历史构造）：tool_calls 与 tool 结果必须成对且顺序正确。这类错误**单测很难覆盖**——只能靠真实生成暴露，这也正是 S5 必须独占一轮、且必须看真实产物的原因。

### 7.2 收敛重复代码

| 重复项 | 现状 | 改法 |
|---|---|---|
| 生成入口 ✅ | ~~`generate{Html,MultiFile,Vue}Project` + `streamGenerate*` 逐行复制~~ | **已完成（2026-10-10，6 步）**：统一为 `generate(type, userMessage, appId)` / `streamGenerate(type, ...)` 两个入口，6 个旧方法删除、控制器 6 处调用点切换。详见下方 |
| SSE 方法 | `AiChatController` 4 个方法几乎一致 | 抽私有 `sseTemplate(Supplier, StreamCallback)` |
| JSON 转义 | `AiCodeGeneratorFacade.toJsonString` 手写 + 内联 3 处 | 改用注入的 `ObjectMapper` |
| 文件遍历 | `VueProjectAgent.collectCodeFiles/relativePath` 与 `CodeFileWriteService.walkDir/relativePath` 两份 | 收敛到 `CodeFileWriteService` |

### 7.3 死代码 / 命名 / 注释

#### 7.2.1 生成入口收敛实测（2026-10-10，6 步）

收敛后只剩两个入口：`generate(type, userMessage, appId)`（阻塞）与
`streamGenerate(type, userMessage, appId, callback)`（线程池 + 回调）。
**唯一按类型分叉的地方被抽成了 `afterGenerate(type, appId)`**：

```
vue        → codeFileWriteService.deleteVueDist(appId)   // 源码更新后失效旧构建产物
html / multi_file → verifyAndScheduleFix(type, appId)     // 交付前渲染验证
```

三条路径（含原先独立实现的 vue 流式入口）此前是**四份近乎逐行复制**的代码，现在共用一套收尾。

**顺带修掉一个不一致（行为变更，已实测）**：原先 **vue 的非流式入口没有 appId 互斥**
（html/multi_file 与 vue 的*流式*入口都有），与 plan-refactor P0-2"所有生成入口统一走互斥"不符。
收敛后 vue 也走同一层包装。实测：对同一 appId 连发两次 vue 生成，第二次被拒
（`{"code":50001,"message":"该应用正在生成或修改中，请稍候再试"}`），第一次正常完成。

**三种类型各跑一次真实生成**：html（200 / `done` / 渲染验证 3/3 转修复）、multi_file（200 / `done` /
渲染验证 1/3 转修复）、vue（200 / `done`，且**未**误跑渲染验证——走的 vue 分支）；38 用例通过。

- 删除 [`Main.java`](src/main/java/com/mofanyi/Main.java)（无引用的 `Hello world`）。
- **移除死依赖 Sa-Token**：`sa-token-spring-boot3-starter` 与 `sa-token-redis-jackson` 仅在 `pom.xml` 声明，`src` 与 `resources` 均零引用（`rg "StpUtil|SaCheck|cn.dev33" src/main/java` 与 yml 搜索均为空）；鉴权实际走 `HttpSession` + 自写 `@AuthCheck` AOP。
- `AiServiceFactory` 实为配置类 → 改名 `CodeGenPromptConfig` 并移入 `config` 包。
- `projectDownloadService` 类名小写开头 → 改 `ProjectDownloadService`。
- `CodeGenTypeEnum.getEnumByValue` 返回 `null` → 改 `Optional` 或在调用处统一 `throwIf`。
- **修正 `VueProjectAgent` 类注释**：它写"手动 tool-calling loop 突破 token 上限"，实际 `runToolLoop` 在首次成功后 `break`，工具由框架在**一次** `stream()` 内自动执行，`MAX_STEPS=20` 形同摆设。注释必须与实现对齐（或让实现真正变回循环），否则比没注释更危险。

### 7.4 前端拆分

`AppGeneratePage.vue` 2100+ 行单文件组件，按关注点拆 composables：

`useGenerationStream`（SSE 消费）· `usePreview`（代码/网页预览 + 文件树）· `useVisualEdit`（点选改源码）· `useManualEdit`（样式/文字面板）· `useChatHistory`（游标分页）。

---

## 八、目标后端包结构（重构后）

```
com.mofanyi
├── controller            # 仅参数校验 + 委托，不含业务逻辑
├── service
│   ├── app              # AppService / AppAuthService / AppDeployService / AppFeaturedService
│   ├── chat             # ChatHistoryService
│   └── manualedit       # ManualEditService
├── generation           # 生成链路（从 core 归拢）
│   ├── facade            # AiCodeGeneratorFacade
│   ├── task             # GenerationTask / GenerationTaskRegistry / StateMachine
│   ├── agent            # VueProjectAgent + tool loop
│   ├── tool             # writeFile / readFile / searchImage / diagram / finish
│   ├── image            # ImageAutoFiller / SiteImageService / ProjectCodeScanner
│   └── build            # VueBuildService / TypeScriptBuildService
├── ai                   # prompt 注册、模型工厂
├── infra                # cos / redis / metrics / screenshot
├── config               # 配置类（含 CodeGenPromptConfig）
├── mapper / entity / model
└── common / exception / annotation / aop
```

> 原则：`core` 这个"杂物间"包被拆解——生成链路进 `generation`、基础设施进 `infra`、配置进 `config`。

---

## 九、里程碑与执行顺序

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1（P0）** | 生成任务注册表（互斥 + 取消）、状态机终态回收、**交付前渲染验证** | 第二/四/五章验收项全绿 |
| **M2（P1）** | 授权统一、手动编辑冲突、**素材先行**、最小测试、Flyway、线程池 | 归属校验收敛为 1；图片主导页面素材来自预选清单；测试可跑；迁移脚本可重放 |
| **M3（P2）** | 拆巨型类、收敛重复、命名/死代码、前端 composables | 无单文件 > 400 行；`rg` 无重复实现 |

**关键顺序约束**：M1 必须先于 M2/M3——它是后续所有重构的安全网（可安全取消、可拒绝并发）。

---

## 十、明确不做 / 暂缓

- **不重构可观测性**：现有分层设计正确，保持即可。
- **不引入微服务/领域驱动重构**：当前是合理的单体，拆分服务化收益低于成本。
- **不重写前端框架**：只需组件拆分，不换栈。
- **`picture/91485654_p1.jpg`**：未被任何代码引用，暂不入库，由作者决定去留。

---

## 十一、验收总清单

- [ ] 点"停止生成"后确实中断，状态 `stopped`，可立即重生成。
- [ ] 同一应用并发生成被拒绝或排队，磁盘无交叉写入。
- [ ] 崩溃/超时的 `generating` 记录能被自动回收为 `failed`。
- [ ] 生成后经 3 档视口渲染验证，图裂/溢出/console error 能被自动发现并进入修复。
- [x] 图片主导页面围绕预选素材清单构建（2026-10-10 实测：8 幅作品 src 全部来自清单），`autoFillImages` 正则补图被移除。
- [ ] 全项目归属校验只剩 1 处实现。
- [ ] 手动编辑在 AI 重新生成后不丢失。
- [ ] `mvn test` 有可运行的测试且覆盖 `ToolPaths` 路径穿越。
- [x] 数据库可由迁移脚本一键重建（2026-10-10 实测：空库启动即建全 5 张表）。
- [ ] 无 `GENERATING_TASKS` 式死代码、无与实现矛盾的类注释。
