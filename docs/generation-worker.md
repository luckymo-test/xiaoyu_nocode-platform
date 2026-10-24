# Java Generation Worker 运行手册

> 日期：2026-10-24  
> 状态：Phase C.5 已完成  
> 制品：与 Control API 共用同一个 Spring Boot JAR，通过运行模式和 Profile 装配

## 一、运行模式

`codegen.jobs.execution-mode` 控制生成执行链是否装配：

| 模式 | 用途 | Job 消费 | Agent / 模型 | 业务 HTTP |
|---|---|---:|---:|---:|
| `embedded` | 本地开发、应急回退 | 是 | 是 | 是 |
| `api` | 独立控制面 | 否 | 否 | 是 |
| `worker` | 独立 Generation Worker | 是 | 是 | 否 |

默认仍是 `embedded`，避免已有本地启动方式失效。生产拆分使用 `api + worker`。

## 二、启动

同一 JAR 分别启动：

```powershell
java -jar target/fanyi-yun-picture-backend-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=location,api
```

```powershell
java -jar target/fanyi-yun-picture-backend-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=location,worker `
  --codegen.jobs.worker-id=generation-worker-1
```

开发时也可以直接运行：

```text
com.mofanyi.worker.ControlApiApplication
com.mofanyi.worker.GenerationWorkerApplication
```

关键配置：

```yaml
codegen:
  jobs:
    execution-mode: api  # 或 worker / embedded
    queue: db            # db 或 redis
  generation-worker:
    consumer-concurrency: 2
    lease-seconds: 120
    cancel-poll-millis: 1000
```

Worker Profile 默认：

- `server.port=-1`，不暴露业务 Controller；
- `management.server.port=8126`，只开放健康与指标；
- `codegen.verify.enabled=false`，Worker 不启动浏览器；
- 如仍尝试开启浏览器验证，Worker 启动直接失败。

## 三、进程边界

### Control API

- 保留登录、Session、RBAC、Job 创建/查询/取消、Revision、SSE；
- `GenerationJobRunner`、`GenerationJobDispatcher`、`AgentToolLoop`、
  `AiCodeGeneratorFacade`、Vue 构建入口和旧 `/ai/*` 直连生成 Controller 不装配；
- `spring.main.lazy-initialization=true`，避免无关生成 Bean 被意外实例化；
- 不执行模型调用、npm 或浏览器截图；
- Vue 构建/预览和封面截图在 API 模式明确跳过，等待 Phase D/E 的 Execution Worker。

### Generation Worker

- 只消费持久化 Job；
- 使用与 API 相同的 MySQL、Redis、COS、Template、Skill 和 Artifact 契约；
- 不通过 JVM 内存与 API 传递状态；
- 写入 JobEvent、Revision 和 Artifact；
- 使用 Lease、heartbeat、`fencingToken` 防止旧 Worker 恢复后覆盖新结果；
- 消费线程使用固定大小有界线程池，默认并发 2。

## 四、取消与恢复

运行中取消不再调用 API 进程内的 `GenerationTaskRegistry`：

1. API 将 Job 条件更新为 `CANCEL_REQUESTED` 并写入事件；
2. Worker 每秒读取数据库取消状态；
3. 命中后调用当前 Worker 内的协作取消句柄；
4. Agent 在下一个工具/文件副作用边界退出；
5. Worker 用当前 `fencingToken` 将 Job 终结为 `CANCELLED`。

Worker 崩溃后：

- API 查询、SSE 和取消接口不依赖 Worker 存活；
- Lease 到期后 Job 被重新入队；
- 旧 Worker 的 `fencingToken` 失效，不能继续写事件、Artifact 或 Revision。

`WAITING_RETRY` 状态的 Job 现在可以由 Worker 再次 claim，不会出现重试消息已投递但无法执行的问题。

## 五、运行时版本快照

V15 在 Job 创建时固化：

- `stackProfileId + stackProfileVersion`；
- `skillsJson + skillsSnapshotJson`。

Worker 执行前校验：

- StackProfile 仍然存在、可用且与生成类型兼容；
- 当前 StackProfile 版本与 Job 快照一致；
- Skill 仍然存在、与 Profile 兼容且版本未变化；
- Template ID、version、hash 与 Job 快照一致。

旧 Job 没有 V15 快照时继续兼容执行，但只校验当前注册表是否可用。

## 六、可观测性

- API 管理端点：`/actuator/health`、`/actuator/prometheus`；
- Worker 管理端点同路径，默认独立端口 `8126`；
- Worker 健康详情包含 mode、workerId、消费并发和 Lease 秒数；
- 队列积压、运行中、等待重试和死信数量继续走 Prometheus；
- Job 阶段、渲染修复状态和最终结果继续写 `generation_job_event`。

## 七、验证结果

- 135 个默认后端测试通过，18 个外部探针按约定跳过；
- API 模式上下文不存在 Runner、Dispatcher、Agent、Facade、Vue 构建和旧直连 Controller；
- Worker 模式上下文包含完整消费链和健康指示器；
- MySQL 5.7 与 8.4 均通过 V1～V15 和 Job 重试 claim 探针；
- 真实双进程冒烟：
  - API 监听 8130，管理端点 8131；
  - Worker 不监听业务端口，仅管理端点 8132；
  - 两个进程健康均为 UP；
  - API 模式旧 `/ai/chat` 直连入口返回 404；
  - 杀死 Worker 后 API 健康仍为 UP。

## 八、灰度与回滚

推荐顺序：

```text
单实例 embedded 保持默认
→ 同库启动一个 worker，仅投递测试 Job
→ API 切到 execution-mode=api
→ Worker 逐步提高 concurrency
→ 观察租约回收、取消延迟、SSE 连续性和模型错误率
```

回滚：

1. 停止新 Worker 消费；
2. 将 API 恢复为 `embedded`；
3. 重启 API；
4. 等待 Lease 到期后由 embedded Dispatcher 接管遗留 Job。

数据库无需回滚即可兼容 V15 新增快照列。

## 九、Phase D 衔接

Generation Worker 当前不执行浏览器验证，这是刻意边界。Phase D 将：

- 把 Selenium 替换为 Playwright Execution Worker；
- 通过 `BrowserVerifierPort` 接收验证任务；
- 将截图、console、overflow、network 和 trace 作为 Artifact 回传；
- 恢复 Worker 生成后的独立渲染验证闭环。
