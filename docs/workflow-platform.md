# Workflow v2 与平台托管 Data/API

> 日期：2026-10-26
> 状态：核心代码、数据库迁移、前端入口与全栈 Compose 已实现；
> 真实模型多 Agent 基线、真实容器启动和浏览器端完整 E2E 仍需单独验收。

## Workflow v2

新生成 Job 默认 `workflowVersion=2`：

1. Requirement Agent 提取目标、用户、功能、非目标和验收标准。
2. Architect Agent 生成页面、路由、模型、角色、权限和 API actions。
3. UI Agent 生成页面版块、视觉方向、素材需求和可访问性检查。
4. Job 进入 `WAITING_CONFIRMATION`，确认前不创建草稿工作区、不写源码。
5. 用户确认后 Planner Agent 生成任务 DAG。
6. Coder 复用现有 AgentToolLoop；Reviewer、Deploy 由持久化步骤驱动。

用户可提交补充要求重新生成方案。任何计划变化都会产生新的 `planHash`，旧确认立即失效。

## 状态与恢复

新增 Job 状态：

- `WAITING_CONFIRMATION`
- `PAUSE_REQUESTED`
- `PAUSED`

暂停在工具/模型边界协作生效。已经成功的前置规划步骤保留；当前 Coder 步骤标记为
`PAUSED`，继续时只重跑未成功步骤。模型和工具调用明细写入 `agent_step` 与
`agent_tool_call`。

## 项目模板

固定 Vue 3 + Vite：

- `task-management`
- `customer-management`
- `content-publishing`
- `client-selection`（相册、标星、留言、匿名分享）

模板自带 Schema、角色权限和示例数据。示例记录带 `isSample=1`，可通过
`DELETE /v1/apps/{appId}/sample-data` 清除，不修改已录入业务数据。

## Data/API

- 运行时接口：`/runtime/v1/apps/{appId}/models/{model}`
- 所有记录强制带 `workspaceId + appId`。
- Schema 随 Revision 固化；运行时按 Schema permissions 校验角色和动作。
- 分享链接只存 Token 哈希。客户用 `POST /runtime/v1/apps/{appId}/sessions`
  换取 HttpOnly Visitor Cookie。
- 生成页面先请求 `/v1/security/csrf`，写请求带 `X-XSRF-TOKEN`。

## 文件、版本与发布

- 源码文件 API 使用 `expectedHash` 乐观锁。
- 手工保存会生成新的不可变 Revision。
- Revision Diff 返回统一文本 Diff 和增删行统计。
- 生成、手工编辑和版本切换都通过独立 Job 在 Worker 中执行。
- 稳定发布目录原子替换，`deployKey` 不随版本变化。
- 取消发布保留 deployKey 但清空 deployedTime，重新发布继续使用同一 URL。

## 本地全栈

```bash
cp .env.example .env
# 填写 DEEPSEEK_API_KEY、MySQL 密码和 Preview 签名密钥
docker compose up --build
```

- 控制台：`http://localhost:8080`
- Control API：`http://localhost:8124/api`
- Preview / 已发布应用：`http://localhost:8088`

Compose 包含 MySQL 8.4、Redis、Control API、Generation Worker、Browser Worker、
Squid 和 Preview Gateway。

## 已知验收边界

- 当前机器没有 Docker，Compose 镜像与容器网络只做了配置解析，未完成真实容器验收。
- 真实 DeepSeek 三阶段规划、任务 DAG、暂停恢复和客户选片 E2E 尚未作为固定探针执行。
- Monaco 编辑器是懒加载分包，不会进入生成工作区首屏主包。

仍待完成的详细清单、优先级和发布门禁见
[platform-mvp-remaining-work.md](platform-mvp-remaining-work.md)。
