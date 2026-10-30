# 小玉零代码平台

> 用一句话生成可预览、可验证、可发布、可回滚的完整 Web 应用。

小玉零代码平台是一个 AI 驱动的网页生成与交付平台。用户只需描述产品需求，系统即可完成需求分析、架构设计、页面规划、代码生成、隔离构建、真实浏览器验证、版本发布和一键部署。

平台面向 Vue 3、React 和 Next.js 等现代前端运行时，通过可扩展的 StackProfile 管理技术栈差异，并将生成、验证和发布统一到 Job、Revision、Artifact 链路中。

## 核心能力

- **需求确认**：Requirement、Architect、UI 和 Planner Agent 先输出结构化方案，用户确认后才开始写入源码。
- **可恢复 Agent 编排**：基于 Job、Outbox 和 Redis Streams 异步执行，支持租约续期、Fencing Token、暂停继续、失败重试和崩溃恢复。
- **多运行时支持**：使用 StackProfile 统一技术栈对应的 Prompt、依赖白名单、构建、部署和验证规则。
- **模型与工具治理**：统一 ModelGateway 支持模型路由、能力预检、Fallback、熔断和调用审计。
- **隔离构建验证**：在受限工作区完成依赖安装和构建，并由 Playwright 在桌面、平板和手机三档视口检查图裂、溢出、空白页、Console 和网络错误。
- **不可变发布版本**：通过 Revision 和 Artifact 管理源码、构建产物、截图与验证报告，支持 CAS 发布、版本对比和回滚。
- **平台托管 Data/API**：生成应用无需自建数据库，通过按 `workspaceId + appId` 隔离的运行时 API 读写业务数据。
- **可观测执行过程**：展示 Agent、任务 DAG、工具调用、文件变更、Token 消耗、构建状态和验证结果。
- **AI 编辑与手动修改**：支持自然语言迭代、元素点选改码和手动样式编辑，并通过快照与幂等重放保护用户改动。

## 技术栈

Spring Boot 3 · Spring AI · MyBatis-Flex · MySQL · Redis Streams · Vue 3 · TypeScript · Vite · Playwright · Docker Compose · 腾讯云 COS
