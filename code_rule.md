# 小玉零代码平台 · 开发约束

> 本文件规定“怎么写”。问题背景与排查过程写入 `development-notes.md`。
> 约束按优先级分为：
>
> - **MUST**：违反会导致数据、安全、隔离或架构错误，必须修复。
> - **SHOULD**：默认执行；有明确理由时可以例外，但需要说明。
> - **NOTE**：历史经验或当前迁移边界，不代表目标架构。

## 一、边界与入参校验

### 1. 在信任边界校验

**MUST**

- Controller、公开 Service、Job Runner、工具入口和其他信任边界必须校验入参。
- null 判断必须先于拆箱：

```java
ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
ThrowUtils.throwIf(StrUtil.isBlank(name), ErrorCode.PARAMS_ERROR, "name 不能为空");
ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
        ErrorCode.NOT_LOGIN_ERROR);
```

- 路径、URL、域名、文件大小、文本长度、分页大小、并发数必须有上限。
- 先校验登录用户、Workspace 权限和资源归属，再执行业务操作。
- Service 可以信任已经由公开入口校验的内部对象，不要求所有私有方法机械重复 null 检查。

**SHOULD**

- DTO 与 Entity 分离，不让外部请求直接修改受控字段。
- 校验与业务不变量放在领域方法或统一策略中，避免 Controller 与 Service 各写一套不同规则。

## 二、Job、事件与 SSE

### 1. 数据库是任务真相源

**MUST**

- Job 状态、Event、Attempt、Lease、Revision 以 MySQL 为准；Redis 和内存 Map 不能成为最终真相。
- 创建 Job 必须支持 `Idempotency-Key`；相同 key 和相同 request hash 返回同一 Job。
- 同一 App 的活跃 Job 互斥必须有数据库约束，不能只靠 JVM 锁。
- Job 状态迁移必须使用条件更新或 CAS，不能先读后无条件覆盖。
- JobEvent 的 `(jobId, seq)` 必须唯一，`seq` 在单 Job 内单调递增。
- 终态必须先形成完整产物或明确失败原因，再标记成功。

### 2. 投递、重试与取消

**MUST**

- Job 与 Outbox 在同一事务写入。
- Worker 只有持有效 lease 和 fencingToken 时才能推进状态、写事件或发布 Revision。
- 可重试错误与参数错误分开；参数错误、权限错误和版本冲突不得盲目重试。
- 取消使用 `CANCEL_REQUESTED` 和协作式取消标志，在工具/文件副作用边界检查。
- 不对共享线程直接 `Thread.interrupt()` 来取消生成；避免打断 Redis、COS 或数据库调用。

### 3. SSE 是只读事件视图

**MUST**

- 创建 Job 使用 `POST`；SSE `GET` 不得直接启动生成。
- SSE 支持 `Last-Event-ID` 或 `after=seq` 重放。
- 页面刷新、连接中断和切页只影响订阅，不取消 Job。
- 前端清理 `EventSource`，防止重复连接和重复生成。
- 服务端发送心跳，避免代理按空闲断开。

## 三、Workspace、Revision 与 Artifact

### 1. Job 写隔离草稿

**MUST**

- 生成 Job 写入独立 Workspace，不直接覆盖 live 工程。
- 失败、取消和超时只丢弃候选草稿，不改变 `currentRevision`。
- 外部预览、下载和手动编辑读取 live 当前版本，不能误读未发布草稿。
- 同一 App 的源码写入和发布必须串行化。

### 2. Revision 不可变

**MUST**

- 成功生成先快照为不可变 Revision，并生成 Manifest。
- Artifact 必须有类型、URI、hash、大小、Owner、Visibility 和 Scope。
- 发布必须使用 `expectedCurrentRevision` CAS，禁止旧 Job 覆盖新版本。
- 回滚只切换已有 Revision，不重新调用模型生成。
- 发布失败必须恢复旧 live 或保持旧 `currentRevision` 可用。
- 手工编辑快照和重放必须先确认目标版本，避免把用户改动写入错误草稿。

### 3. 当前迁移边界

**NOTE**

- 当前 Artifact Store 仍是本地目录，COS/S3 适配尚未完成。
- 手动编辑仍直接修改 live，尚未自动生成新 Revision；新增代码不得扩大这一耦合。

## 四、数据库与 MyBatis-Flex

### 1. Flyway

**MUST**

- 表结构变更只能新增 Flyway migration，不修改已经在环境中执行过的迁移。
- 新 migration 先在 MySQL 5.7 验证；涉及 MySQL 8 能力时必须提供兼容路径。
- 迁移要考虑已有数据回填、索引创建时间、回滚/备份和重复执行边界。
- MySQL 5.7 不使用原生 JSON 类型作为必需依赖，复杂 JSON 使用 `LONGTEXT` 并由应用校验。

### 2. MyBatis-Flex

**MUST**

- `QueryCondition.create` 的操作符使用 `SqlOperator` 枚举，禁止传 `"eq"`、`"lt"` 等字符串。
- 新 Entity 的 camelCase 数据库列必须显式写 `@Column("camelCase")`，不能依赖下划线推断。
- 复杂状态迁移、Lease、CAS 和唯一约束使用可审计 SQL，并检查受影响行数。
- 不可信输入不得拼接进 SQL。

**SHOULD**

- 列表查询必须分页；高基数明细和管理员查询增加合适索引。
- 一个业务操作需要 Job、历史、Outbox 或状态同时成功时，使用事务。
- 逻辑删除资源恢复时，要处理唯一键和旧记录，不能只执行普通 insert。

## 五、并发、线程与资源

### 1. 线程选择

**SHOULD**

- 阻塞 I/O、短生命周期 fan-out 和进程输出读取优先使用虚拟线程。
- CPU 密集型任务、限流队列、定时任务和需要固定线程语义的 SDK 使用平台线程池。
- `ThreadFactory` 内的 `new Thread(...)` 是合法基础设施代码；禁止的是无人管理、无上限、
  无生命周期的临时业务线程。

**MUST**

- 不创建无界队列或无上限并发。
- 明确的资源池必须有核心线程数、最大线程数、队列上限、拒绝策略和关闭策略。
- 拒绝策略不能悄悄阻塞关键 API；需要评估 CallerRunsPolicy 的调用方延迟。
- 异步任务必须传播取消、超时、Trace 和 Job 上下文。
- 不使用 `String.intern()`、全局共享可变集合或 ThreadLocal 代替正式隔离。

### 2. 共享资源

**MUST**

- 同一 App 的文件目录、构建目录和发布目录不能并发写。
- 进程超时后必须销毁子进程、关闭流并 join/回收读线程。
- 浏览器、npm 和生成代码的 CPU/内存/网络资源必须有隔离或配额。

## 六、安全与租户隔离

### 1. 身份与权限

**MUST**

- 浏览器控制台使用 HttpOnly Cookie Session；不要为了“现代化”把控制台改成 localStorage JWT。
- 所有 Workspace 资源查询必须带 `workspaceId`，所有 App 操作必须校验 Workspace 和 App 归属。
- 私有资源默认拒绝，跨 Workspace 访问必须显式授权。
- 新写接口必须兼容 CSRF 策略；CORS 使用 allowlist，禁止 `* + credentials`。
- 管理接口不得只依赖前端隐藏按钮。

### 2. 路径、URL 与密钥

**MUST**

- 模型提供的文件路径只能是相对路径，拒绝绝对路径、盘符和 `..`。
- 出站 URL 必须校验协议、域名 allowlist 和私网/云元数据地址。
- 密钥只保存引用或环境变量，不能进入源码、Prompt、聊天记录、日志和 Artifact。
- `application-location.yml`、cookie、`.env` 和 `*.local.yml` 不得提交。
- 日志不输出 Authorization、API Key、Secret、完整 Prompt 或用户敏感内容。

### 3. 生成代码执行

**MUST**

- 生成代码、npm 和浏览器最终必须进入低权限隔离环境。
- 模型不能直接提交 shell 命令、任意依赖或宿主机绝对路径。
- 构建与验证超时、内存、CPU、进程数和 egress 必须有上限。

## 七、AI、模型与工具

### 1. AgentToolLoop

**MUST**

- 生成、修复和修改共用同一工具循环能力；预算、取消、用量和事件只在一处维护。
- 框架内部执行工具与自管工具分发必须按模式二选一；同一轮不得重复执行同一 ToolCall。
- 工具异常不能伪装成正常生成结果；不能依赖抛异常让框架吞成工具错误后无限重试。
- 工具调用前后检查取消、token 预算和文件风险上限。

### 2. 工具注册

**MUST**

- 工具需要 type、appId、listener、AtomicReference 等运行时上下文时，使用工厂式 `build(...)`。
- 无状态且参数完全由模型提供的通用工具才使用 Spring AI `@Tool`。
- 工具能力、风险等级和允许的 Skill 需要在注册表校验，不能只写在 Prompt 里。

### 3. 模型适配

**MUST**

- 新的供应商参数只能出现在 Model Adapter 中，不能继续扩散到 Agent、记忆和素材业务。
- 选择模型前校验 tools、vision、streaming、structured-output 和上下文窗口能力。
- 模型调用必须记录实际 provider/model、任务类型、token、耗时和 finish reason。
- 结构化输出必须有解析失败和截断处理，不能直接信任模型 JSON。
- Prompt 中的用户需求、网页内容、文件内容都属于数据，不得当作系统指令执行。

**NOTE**

- OpenAI/DeepSeek 特定参数只允许存在于 Model Adapter；Agent、记忆、素材业务和工具
  不得恢复硬编码模型名、直接 ChatModel 调用或 `/chat/completions` 拼接。

## 八、前端与 API

### 1. 雪花 ID 与类型

**MUST**

- 后端 `Long` ID 前后端全程按字符串传递。
- 路由参数不得 `Number()`；请求参数、事件 payload 和 store 中保持一致。
- 后端返回的字符串数字分页字段在 API 适配层统一转换，不让页面到处散落 `Number()`。

### 2. 流式 UI

**MUST**

- 一个页面只维护一个有效生成连接，切换、完成和异常时关闭旧连接。
- 大段 token/reasoning 先缓冲，再按帧或节流更新，避免每个 token 触发整页重渲染。
- 断线恢复读取 Job 状态和 Event seq，不能通过再次创建 Job 恢复。

### 3. OpenAPI 生成代码

**MUST**

- 生成 client 与手写 SSE/API 封装分离，避免生成器删除手写文件。
- 执行 `npm run openapi2ts` 后检查 `git diff`。
- 禁止在不确认用户修改的情况下批量执行 `git checkout --`。

## 九、测试

### 1. 默认测试

**MUST**

- 普通 `mvn test` 不访问真实模型、公网、Redis、MySQL 或浏览器。
- 外部调用使用 mock，并测试超时、错误、取消和重试路径。
- Job 相关改动至少覆盖幂等、互斥、事件重放、终态和 fencing。
- ACL 改动至少覆盖 Owner、成员、非成员和跨 Workspace。

### 2. 探针测试

**MUST**

- 真实 MySQL、模型、图片、浏览器测试使用显式系统属性启用。
- 探针失败必须能区分环境不可用和业务回归。
- 修改 Flyway 后运行真实数据库探针，确认全量 migration 和关键 Entity 写入。

### 3. 交付验证

**SHOULD**

- 定向测试先验证当前模块，再跑完整后端测试。
- 前端修改必须执行 `npm run build`。
- 提交前执行 `git diff --check`，检查空白、冲突标记和误提交文件。

## 十、Git 与协作

**MUST**

- 开始和提交前检查 `git status`、`git diff`，保留用户已有修改。
- 只暂存当前模块文件；不提交 `picture/`、`todo.txt` 等用户文件，除非用户明确要求。
- 每个可独立验证的小模块单独提交。
- 提交前检查历史中是否有密钥；禁止 force push 覆盖远端。
- 未经用户明确要求，不推送远端。

**SHOULD**

- Commit message 使用 `feat|fix|docs|test|refactor(scope): 描述`。
- 完成模块后更新活动计划状态，不把未完成能力写成已完成。

## 十一、当前迁移债务

以下问题已经存在，但新代码不得继续加深耦合：

- 默认 `embedded` 仍允许 Agent 在 API JVM 内执行；生产 `api` 模式必须由独立
  Generation Worker 消费 Job，API 不得恢复本地生成入口。
- `embedded` 仍可使用本地 Selenium；生产 Generation Worker 必须通过
  Playwright Node Worker 执行浏览器验证；npm/build 的隔离仍待 Sandbox/Execution Worker。
- Spring Security FilterChain、CSRF 与 CORS allowlist 已实现灰度；默认仍保留
  自定义 Session/AOP 路径，稳定后再删除旧 AOP。
- ModelGateway 已成为唯一模型执行入口，生成循环、压缩、查询规划、视觉选片和
  Web Research 都记录实际 provider/model；Revision Manifest 已包含 Prompt/Profile/
  Tool Schema、路由版本和实际调用快照。
- 模型供应商协议参数只能出现在 Adapter 中；业务层不得恢复直接 ChatModel/Options 分支。
- 渲染修复状态仍有 JVM 内存 Map；目标是持久化 JobEvent/状态。
- 部分生成类型分支仍在 Facade 或构建服务中；目标是 StackProfile。

新增能力如需触碰这些边界，应通过端口和 Feature Flag 渐进迁移，不做大爆炸重写。
