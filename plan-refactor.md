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
| **P1** | 归属校验散落 3 处 | 越权遗漏风险 | AiChatController×4、ManualEditController×1、AppServiceImpl×1 |
| **P1** | 手动编辑被重新生成整文件覆盖 | 用户改动静默丢失 | `ManualEditService` vs `writeFile` 覆盖 |
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

## 五、P1：授权、冲突、测试、迁移

### 5.1 归属校验统一为一处

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

### 5.2 手动编辑与重新生成的冲突

证据：`ManualEditService` 往 `css/styles.css` / `index.html` 注入带 UUID 标记块，而 AI 重新生成用 `writeFile` 整文件覆盖 → 用户手动改动静默丢失。

改法（二选一）：

- **方案 A（推荐，彻底）**：手动编辑改为写入独立文件 `css/user-edits.css`，由 `index.html` 额外 `<link>` 引用。生成只覆盖 AI 产物，用户文件天然不受影响；`readProjectCode` 拼装时带上它即可。
- **方案 B（保守）**：生成前扫描并备份所有 `@user-edit` 标记块，生成后重放。

验收：手动改样式 → 触发 AI 重新生成 → 手动改动仍在，预览/下载/部署一致。

### 5.3 补最小测试集

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

### 5.4 数据库迁移工具

现状：`sql/` 是散落的手工 `ALTER TABLE`，无版本、无回滚。

改法：引入 **Flyway**（Spring Boot 内置支持），把现有 `sql/*.sql` 收敛为 `V1__init.sql` 等带版本脚本，`baseline-on-migrate=true` 兼容已有库。

### 5.5 线程池归 Spring 管理

证据：`AiCallListener` L27 自建 `Executors.newVirtualThreadPerTaskExecutor()`，违反项目规范 #63"线程池归 Spring 管理"。

改法：定义受管的 `ExecutorService` Bean（虚拟线程执行器），`AiCallListener` 构造注入。

---

## 六、P2：结构瘦身与一致性

### 6.1 拆巨型类

**`VueProjectAgent`（722 行）** 按职责拆为：

| 抽出物 | 职责 |
|---|---|
| `CodeGenPromptRegistry` | 按类型选 prompt（现 `promptForType`） |
| `ImageAutoFiller` | `autoFillImages` / `fillImagesInContent` |
| `ProjectCodeScanner` | `scanAndSaveImages` / `collectCodeFiles` / `relativePath` |
| `TokenUsageAccumulator` | `accumulateUsage` / `TokenUsage` |
| `AgentToolLoop` | `runToolLoop` / `streamAndAggregate` |

**`AppServiceImpl`（576 行）** 按职责拆为：`AppDeployService`（含 `doDeploy`）、`AppFeaturedService`（精选审核）、`AppQueryService`（分页/QueryWrapper）、`AppConverter`（VO 转换）、`AppRecycleService`（回收站/物理删除）。

### 6.2 收敛重复代码

| 重复项 | 现状 | 改法 |
|---|---|---|
| 生成入口 | `generate{Html,MultiFile,Vue}Project` + `streamGenerate*` 逐行复制 | 统一为 `generate(type, ...)` / `streamGenerate(type, ...)` |
| SSE 方法 | `AiChatController` 4 个方法几乎一致 | 抽私有 `sseTemplate(Supplier, StreamCallback)` |
| JSON 转义 | `AiCodeGeneratorFacade.toJsonString` 手写 + 内联 3 处 | 改用注入的 `ObjectMapper` |
| 文件遍历 | `VueProjectAgent.collectCodeFiles/relativePath` 与 `CodeFileWriteService.walkDir/relativePath` 两份 | 收敛到 `CodeFileWriteService` |

### 6.3 死代码 / 命名 / 注释

- 删除 [`Main.java`](src/main/java/com/mofanyi/Main.java)（无引用的 `Hello world`）。
- `AiServiceFactory` 实为配置类 → 改名 `CodeGenPromptConfig` 并移入 `config` 包。
- `projectDownloadService` 类名小写开头 → 改 `ProjectDownloadService`。
- `CodeGenTypeEnum.getEnumByValue` 返回 `null` → 改 `Optional` 或在调用处统一 `throwIf`。
- **修正 `VueProjectAgent` 类注释**：它写"手动 tool-calling loop 突破 token 上限"，实际 `runToolLoop` 在首次成功后 `break`，工具由框架在**一次** `stream()` 内自动执行，`MAX_STEPS=20` 形同摆设。注释必须与实现对齐（或让实现真正变回循环），否则比没注释更危险。

### 6.4 前端拆分

`AppGeneratePage.vue` 2100+ 行单文件组件，按关注点拆 composables：

`useGenerationStream`（SSE 消费）· `usePreview`（代码/网页预览 + 文件树）· `useVisualEdit`（点选改源码）· `useManualEdit`（样式/文字面板）· `useChatHistory`（游标分页）。

---

## 七、目标后端包结构（重构后）

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

## 八、里程碑与执行顺序

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1（P0）** | 生成任务注册表（互斥 + 取消）、状态机终态回收 | 第二/四章验收项全绿 |
| **M2（P1）** | 授权统一、手动编辑冲突、最小测试、Flyway、线程池 | 6 处校验收敛为 1；测试可跑；迁移脚本可重放 |
| **M3（P2）** | 拆巨型类、收敛重复、命名/死代码、前端 composables | 无单文件 > 400 行；`rg` 无重复实现 |

**关键顺序约束**：M1 必须先于 M2/M3——它是后续所有重构的安全网（可安全取消、可拒绝并发）。

---

## 九、明确不做 / 暂缓

- **不重构可观测性**：现有分层设计正确，保持即可。
- **不引入微服务/领域驱动重构**：当前是合理的单体，拆分服务化收益低于成本。
- **不重写前端框架**：只需组件拆分，不换栈。
- **`picture/91485654_p1.jpg`**：未被任何代码引用，暂不入库，由作者决定去留。

---

## 十、验收总清单

- [ ] 点"停止生成"后确实中断，状态 `stopped`，可立即重生成。
- [ ] 同一应用并发生成被拒绝或排队，磁盘无交叉写入。
- [ ] 崩溃/超时的 `generating` 记录能被自动回收为 `failed`。
- [ ] 全项目归属校验只剩 1 处实现。
- [ ] 手动编辑在 AI 重新生成后不丢失。
- [ ] `mvn test` 有可运行的测试且覆盖 `ToolPaths` 路径穿越。
- [ ] 数据库可由迁移脚本一键重建。
- [ ] 无 `GENERATING_TASKS` 式死代码、无与实现矛盾的类注释。
