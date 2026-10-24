# 小玉零代码生成平台

AI 应用生成平台。用户通过一句话创建网站或前端工程，平台负责生成、素材、构建、渲染验证、
版本发布、修改与部署。

## 当前技术栈

- 后端：Java 21、Spring Boot 3.5、Spring AI、MyBatis-Flex、Flyway；具体版本以 `pom.xml` 为准。
- 前端：Vue 3、TypeScript、Vite、Pinia、Ant Design Vue。
- 基础设施：MySQL、Redis、腾讯云 COS。
- 当前数据库兼容基线为 MySQL 5.7；新 SQL 需要同时考虑 MySQL 8 迁移。

## 当前核心架构

- 生成请求创建持久化 `GenerationJob`，SSE 只读取事件，不直接触发生成。
- App 归属 `Workspace`，资源访问由 Workspace Role 和 App 归属共同约束。
- 每次 Job 使用隔离 Workspace；成功后才生成不可变 `Revision` 并 CAS 发布。
- Job、Event、Outbox、Revision 和 Artifact 以 MySQL 为真相源。
- Redis 用于 Session、热记忆、限流和可选队列，不作为任务状态的唯一来源。
- Lease、heartbeat、`fencingToken` 保护多 Worker 和崩溃恢复。
- 默认 `embedded` 模式仍在 API 进程执行；生产可切换为 `api + worker`，
  由独立 Generation Worker 消费 Job，API 不装配 Agent/模型执行链。
- 浏览器和 npm 当前仍在本机执行；独立 Playwright/Sandbox Worker 仍在后续计划中。
- Spring Security、CSRF 与 CORS allowlist 已实现灰度层，默认开关关闭；
  上线顺序见 [docs/security-migration.md](docs/security-migration.md)。
- ModelRegistry / ModelGateway 已成为唯一模型执行入口，覆盖生成、修复、修改、压缩、
  查询规划、视觉选片和研究；业务层旧 OpenAI Options 分支已移除；
  Revision Manifest 记录 Prompt/Profile/Tool Schema、路由版本和实际模型调用，
  见 [docs/model-gateway.md](docs/model-gateway.md)。

## 必读文档

1. 开发约束：[code_rule.md](code_rule.md)
2. 后续任务唯一执行计划：[plan-platform-remaining-work.md](plan-platform-remaining-work.md)
3. 历史架构与已完成记录：[plan-platform-rearchitecture.md](plan-platform-rearchitecture.md)
4. 踩坑与排查记录：[development-notes.md](development-notes.md)
5. 安全灰度与回滚：[docs/security-migration.md](docs/security-migration.md)
6. 模型网关与剩余适配：[docs/model-gateway.md](docs/model-gateway.md)
7. Generation Worker 运行与回滚：[docs/generation-worker.md](docs/generation-worker.md)

新问题追加到 `development-notes.md` 末尾并保持连续编号，不要把“最新编号”硬编码到其他文档。

## 默认执行原则

- 先检查现有实现和 Git 工作区，再开始修改。
- 小模块实施、验证、提交；不把整个重构计划一次性完成。
- 保留现有 AgentToolLoop、Vue 控制台和 MyBatis-Flex，不做无收益重写。
- 修改 Job、Revision、ACL、Outbox 或安全边界时，先补测试。
- 不提交真实密钥、本地配置、用户未跟踪文件或无关工作区修改。

## 本机构建命令

PowerShell：

```powershell
$env:JAVA_HOME = 'D:\computer tools\java\JDK'
& 'D:\computer tools\IDEA\IntelliJ IDEA 2023.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' -o test
```

如果上述 JDK 路径不存在，可使用：

```powershell
$env:JAVA_HOME = 'C:\Users\莫凡毅\.jdks\corretto-21.0.10'
```

前端：

```powershell
cd xiaoyuZeroCode-front
npm run build
```

真实基础设施探针：

```powershell
$env:JAVA_HOME = 'D:\computer tools\java\JDK'
& 'D:\computer tools\IDEA\IntelliJ IDEA 2023.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -o -Dtest=GenerationJobPersistenceProbeTest -DcodegenProbe=true test
```

## 运行

- 后端：端口 `8124`，context-path `/api`。
- 前端：Vite 开发服务器通过 `/api` 代理后端。
- 数据库迁移只能放在 `src/main/resources/db/migration/`，由 Flyway 执行。
- 不要新增手工 `sql/*.sql` 迁移入口。

## 敏感文件

- `src/main/resources/application-location.yml` 包含本机数据库和云服务密钥。
- 该文件必须保持本地忽略状态，不得读取密钥到日志、Prompt、测试输出或提交。
- `*.local.yml`、cookie 文件、`.env` 和临时部署目录不得提交。

## OpenAPI 前端代码

`npm run openapi2ts` 会重建 `src/api/`，可能删除手写 SSE/API 封装。

执行后必须检查 `git diff`。不要直接使用会覆盖用户修改的批量恢复命令；
长期方案是把生成代码和手写 API 分到不同目录。

## Git

- 默认分支：`main`。
- 远端：`https://github.com/luckymo-test/xiaoyu_nocode-platform.git`。
- 提交前检查 `git status` 与 `git diff`，只暂存当前模块文件。
- 未经用户明确要求，不执行 force push。
- 不删除或重置用户已有修改。
