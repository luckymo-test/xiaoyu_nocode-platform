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
| **P1** | 素材"事后正则补图"而非素材先行 | 页面围绕空占位设计，补图靠猜 | `VueProjectAgent.autoFillImages` 在 `runToolLoop` 之后 |
| **P1** | 核心链路零测试 | 后续重构无安全网 | `src/test` 无文件 |
| **P1** | 无数据库迁移工具 | 换环境/回滚靠手工 | `sql/` 散落 ALTER |
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

### 6.5 线程池归 Spring 管理

证据：`AiCallListener` L27 自建 `Executors.newVirtualThreadPerTaskExecutor()`，违反项目规范 #63"线程池归 Spring 管理"。

改法：定义受管的 `ExecutorService` Bean（虚拟线程执行器），`AiCallListener` 构造注入。

---

### 6.6 素材先行（替代事后正则补图）

> 详见 [plan-agent-pipeline.md](plan-agent-pipeline.md)——素材先行的完整论证与方法论对照。

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

前四步的判据是"日志与产出跟改动前一致"；而 S5 的目的是**改变**生成过程（让逐步预算真正生效）。
用它自己的话讲：**"成功"和"回归"在观测上必须能分开**，所以另立标准。

**① 可回滚（最重要）**

- 自管循环做成开关：`codegen.agent.self-managed-loop.enabled`，**默认 false**（新旧路径并行存在，不是替换）
- 判据：开关 false 时行为与改动前一致；true 时走新路径
- 理由：行为变更若不能一键退回，一旦出问题只能回滚整个提交——把风险从"整次重构"缩小到"一个开关"

**② 预算真正生效（S5 存在的唯一理由）**

- 新增可观测：每轮开始前打印 `[agent] round=N 累计token=X/上限Y 已耗时=Zs`
- 判据（可证伪）：构造超预算场景，确认**在轮次之间被拦下**，且
  `totalTokens ≤ 上限 + 单轮最大增量`（而不是现状的"整轮烧完才检查"）
- **现状反证数据**：三次实测 `218313` / `276833` / `288824`，均 > 20w 上限却未拦下

**③ 上下文增长被压住（附加收益）**

- 判据：同一复杂 prompt，**末轮 promptTokens ÷ 首轮 promptTokens ≤ K**（K 需实测标定，初值 3）
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
| 生成入口 | `generate{Html,MultiFile,Vue}Project` + `streamGenerate*` 逐行复制 | 统一为 `generate(type, ...)` / `streamGenerate(type, ...)` |
| SSE 方法 | `AiChatController` 4 个方法几乎一致 | 抽私有 `sseTemplate(Supplier, StreamCallback)` |
| JSON 转义 | `AiCodeGeneratorFacade.toJsonString` 手写 + 内联 3 处 | 改用注入的 `ObjectMapper` |
| 文件遍历 | `VueProjectAgent.collectCodeFiles/relativePath` 与 `CodeFileWriteService.walkDir/relativePath` 两份 | 收敛到 `CodeFileWriteService` |

### 7.3 死代码 / 命名 / 注释

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
- [ ] 图片主导页面围绕预选素材清单构建，`autoFillImages` 正则补图被移除。
- [ ] 全项目归属校验只剩 1 处实现。
- [ ] 手动编辑在 AI 重新生成后不丢失。
- [ ] `mvn test` 有可运行的测试且覆盖 `ToolPaths` 路径穿越。
- [ ] 数据库可由迁移脚本一键重建。
- [ ] 无 `GENERATING_TASKS` 式死代码、无与实现矛盾的类注释。
