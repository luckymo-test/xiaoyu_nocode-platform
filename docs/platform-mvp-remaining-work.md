# 零代码平台完整 MVP：未完成工作清单

> 日期：2026-10-27
> 当前基线：Workflow v2、平台托管 Data/API、项目模板、文件编辑、异步发布、
> Preview Gateway、用量面板和全栈 Compose 的核心代码已实现。
> 本文只记录仍未完成或尚未真实验收的部分。未实际执行的测试不得标记为通过。

## 一、当前已验证事实

- 后端全量测试：173 通过，25 个外部真实探针按约定跳过。
- 前端 TypeScript + Vite 生产构建通过。
- MySQL 5.7 已真实执行 V16、V17；历史 V1～V15 继续通过 Flyway 校验。
- 本机 Control API 冒烟通过：
  - 创建 `client-selection` 客户选片模板；
  - 生成 1 份 Runtime Schema；
  - 写入 4 条示例记录；
  - 创建匿名分享链接并换取访客会话；
  - 客户身份成功读取相册；
  - 冒烟项目和相关数据已清理。
- Compose YAML 可解析，共 8 个服务。
- 当前机器没有 Docker，未执行容器构建和 `docker compose up`。

## 二、P0：MVP 上线阻塞项

以下问题会直接影响“真实可运行、可恢复、安全隔离”的交付承诺，应在正式发布前完成。

### 2.1 真实模型 Workflow v2 端到端验收

状态：未执行。

需要验证：

- Requirement → Architect → UI 在真实模型下返回可解析 JSON。
- 用户确认前没有任何源码文件写入。
- 修改方案后 `planHash` 更新，旧确认失效。
- Planner 固定 DAG 为 `code-project → review-project → deploy-project`，
  三个成功状态都对应真实执行。
- 暂停后重启 Worker，再继续时只重跑未完成的 Coder 步骤。
- 取消、失败重试、步骤级重试和 Worker 租约接管均能从 SSE 事件恢复。
- `agent_plan`、`agent_step`、`agent_tool_call` 中能看到真实的输入、输出、
  工具结果、文件哈希、错误和耗时。

建议固定验收需求：

1. 客户选片系统。
2. 任务管理模板。
3. 客户管理模板。
4. 内容发布模板。

验收要求：

- 生成、Review、发布采用真实 DeepSeek 调用，不以 mock 代替。
- 失败允许记录为失败，但不能通过跳过探针或手动改库制造成功。
- 每个场景保存 Job ID、Revision ID、截图、Render Report 和耗时/token。

### 2.2 全栈 Compose 与容器隔离真实验收

状态：只有配置，没有容器运行证据。

需要验证：

- `docker compose up --build` 能拉起 MySQL、Redis、API、Generation Worker、
  Browser Worker、Squid、Preview Gateway 和前端。
- MySQL 8.4 从空库执行 V1～V17。
- API 进程不装配 Agent、模型、npm 和 Playwright 执行链。
- Generation Worker 能消费 Job、执行构建并通过 Redis 调用 Browser Worker。
- Browser Worker 只通过 Squid 访问 allowlist 域名。
- 工作区和部署命名卷的 UID/GID、读写权限和重启持久化正确。
- Preview Gateway 能读取共享工作区、代理 Runtime API 并设置安全响应头。
- Worker 被 OOM/kill 后 API 不受影响，Lease 到期后 Job 可被接管。

破坏性探针：

- fork bomb；
- 无限循环；
- 内存超限；
- 强制销毁；
- 网络代理绕过；
- 只读 rootfs 写入；
- Browser/Generation Worker 非 root 校验。

### 2.3 生成应用的执行过程恢复

状态：后端状态已持久化，前端只保存当前页面的 Job ID。

缺口：

- 刷新页面、换设备或关闭标签页后，前端不知道当前 App 最新 Job。
- 没有“查询 App 当前/最近 Job”的 API。
- 处于 `WAITING_CONFIRMATION`、`PAUSED`、`WAITING_RETRY` 的任务在刷新后
  无法自动重新打开确认卡和执行面板。

需要实现：

- `GET /v1/apps/{appId}/generation-jobs/latest`。
- App 页面加载时恢复 Job、Plan、Steps、工具调用和 SSE 游标。
- 按 App 展示进行中的任务，并避免重复创建新 Job。

验收：

- 确认卡出现后刷新页面，确认卡仍存在。
- 暂停后关闭页面，重新打开可继续。
- Worker 重启后执行面板能继续显示同一 Job 的真实进度。

### 2.4 Runtime Data/API 权限语义

状态：基本 CRUD、角色动作和租户条件已实现，深度策略未完成。

未完成：

- 分享链接中的 `models` 权限已存储，但 Runtime 请求没有再次校验该模型范围。
- `apiActions.ownership` 只做了部分基础判断，缺少字段级和关系级 ownership。
- 分享链接不能限制单条记录范围，例如只能读取指定相册。
- 没有阻止客户端写入未知字段、服务端受控字段或伪造 `ownerId`。
- Schema 中的 `unique=true` 尚未落为业务唯一约束。
- 关系读取、联表查询和筛选 API 未实现；当前列表只有 `limit`。
- 没有 Schema 兼容性检查，破坏性字段变更没有迁移计划。
- 没有数据版本迁移、回滚、备份和保留策略。

上线前至少需要：

- 所有分享访问同时校验 Token Scope、Schema Policy 和记录 ownership。
- 字段白名单校验；客户端不可写 owner、tenant、audit 字段。
- 唯一字段在应用层加数据库级去重保护。
- 删除字段、修改类型、删除模型必须拒绝或进入显式迁移确认。
- 增加跨 Workspace、跨 App、伪造 Token、越权模型、伪造 owner 的集成测试。

### 2.5 Preview 默认隔离

状态：已提供“隔离预览”签名 URL，但生成工作区默认仍使用同源 `srcdoc`。

未完成：

- 普通网页预览尚未默认走 Preview Gateway。
- 发布和回滚后的稳定 URL 尚未完成真实浏览器 E2E。
- 需要验证 Vue 绝对资源路径、SPA fallback 和 Runtime API 在代理路径下都正常。
- 需要验证 Preview Token 过期、撤销、跨用户访问和跨 App 替换均被拒绝。

验收：

- 生成页默认预览使用独立 Origin 的受控代理。
- 平台 Cookie、DOM 和 localStorage 不与管理控制台同源。
- Token 过期后页面不能继续访问。

### 2.6 工具清单与 Prompt 契约不一致

状态：`workflow_planner_prompt.txt` 仍列出
`runCommand/buildProject/testProject/createPreview/publishRevision`，
但 AgentToolLoop 实际只注册了文件、检索、图片、Diagram 和 finish 工具。

需要二选一：

1. 实现受控高层工具并在 Agent 中注册；
2. 从 Planner 的 allowedTools 和 UI 契约中删除未注册工具。

推荐：

- 实现 `buildProject`、`testProject`、`createPreview` 为服务端能力工具；
- `publishRevision` 只允许 Deploy 阶段调用；
- 任意 shell 不直接开放，`runCommand` 只执行命令白名单。

验收：

- 模型看到的工具列表与运行时可执行集合完全一致。
- 未实现工具不能被 Planner 或 Skill 声明为可用。

## 三、P1：MVP 加固项

### 3.1 Planner 任务粒度

当前为与真实执行器对齐，Planner 被限制为固定的三个任务。后续如要实现
真正的页面/模块级恢复，需要：

- 为 Coder Task 增加独立的页面、模型和验收输入。
- 每次 Coder Task 只修改任务声明的文件范围。
- Reviewer 按 Coder Task 聚合结果，但最终仍执行全项目构建和 E2E。
- 失败或暂停只重跑当前任务，不重建整个项目上下文。

### 3.2 Reviewer 业务和安全检查

当前已有：

- 构建；
- 三档视口渲染；
- 图裂、溢出、空白页、console error；
- Aesthetic Review。

仍未完成：

- 依赖和危险代码静态安全扫描；
- Schema API 权限矩阵自动测试；
- 模板关键业务流的 Playwright 脚本；
- Aesthetic Review 自动修复、最大轮次和修复后复验；
- 业务验收标准到可执行测试的自动转换。

### 3.3 模板离线性与可运行保证

当前模板提供：

- 固定 Schema；
- 角色权限；
- 示例数据；
- 初始 Prompt 和视觉模板。

仍未完成：

- 模板不包含可直接复制的 Golden Vue 源码。
- 页面 UI 仍依赖真实模型生成，模型不可用时模板无法直接运行。
- 没有模板预览、版本升级、Golden Screenshot 和 layout regression。

要保证“模板创建后立即可运行”，需要为每个模板提供确定性的 starter source
或离线 fixture，并让模型只做后续增量修改。

### 3.4 文件管理与冲突解决

已完成：

- 文件树；
- Monaco 编辑；
- `expectedHash` 乐观锁；
- 手工保存生成新 Revision；
- 文本 Diff。

仍未完成：

- 新建、删除、重命名、移动文件的 UI/API。
- 并排 Diff、按 hunk 接受/拒绝。
- `VERSION_CONFLICT` 后“保留手工修改 / 覆盖为生成结果 / 重新生成并 rebase”
  的交互。
- 三方合并和冲突项目级预览。

### 3.5 执行过程面板

当前可显示 Step 和最近 20 条工具调用。仍需：

- 完整分页工具日志；
- Artifact 下载/预览；
- 输入与结构化输出查看；
- 文件 Diff 跳转；
- 明确区分规划、执行、验证、发布耗时；
- 错误根因、建议动作、重试范围和下一次重试时间。

### 3.6 审计完整性

目前已有部分：

- 工作区成员变更；
- App 创建、复制、更新、逻辑删除；
- 发布和取消发布。

仍缺少：

- 代码保存与 Revision 创建；
- Generation 确认、暂停、继续、取消、重试；
- 分享链接创建、撤销与 Runtime 越权拒绝；
- 模板创建和示例数据清理；
- 团队与 App 级权限变更查询接口。

### 3.7 用户用量与费用

当前实现：

- 请求时从 `ai_call_log` 求和；
- 扫描 App 工作区计算存储；
- 使用一组全局输入/输出单价估算费用。

仍未完成：

- `user_usage_daily` 的实际增量写入任务。
- 按模型、Provider、日期维护版本化单价。
- 大用户量下的预聚合查询。
- 精确对象存储与 Revision 版本存储统计。
- 与供应商账单核对；当前只能标注为“估算”。

### 3.8 团队与项目列表

当前团队项目列表采用“自己的 App + 可访问 Workspace App”合并后内存分页，
适合 MVP，但不应作为长期方案。

仍需：

- 数据库级可访问 App 查询和稳定分页。
- App 级 ACL，而不只是 Workspace Role。
- 邀请 Token、接受/拒绝、过期和撤销。
- 成员停用/恢复、所有权转移、团队审计查询。

### 3.9 数据库与性能

- V16、V17 目前只有 MySQL 5.7 实机迁移证据，需要补 MySQL 8.4。
- 需要大表上的 Runtime 记录、Tool Call、Job Event 索引和归档策略。
- 需要备份、恢复、迁移回滚演练。
- 当前前端主包约 1.63 MB，Monaco 懒加载约 2.3 MB；后续需要进一步拆包。

## 四、P2：已确认但不在本轮 MVP 内的扩展

- Backend Function / Scheduled Job / SecretReference。
- 生成应用的后端源代码运行时；当前采用平台托管 Data/API。
- 视频、音频、3D 和字体素材处理链。
- 全量 Artifact 的 COS/S3 对象存储适配。
- 应用流量 Dashboard、版本转化漏斗和数据采样。
- Community Fork/Remix、评论、评分、举报、版权申诉。
- 100 Job 并发 30 分钟压测、故障注入和成本运营指标。
- 多地域部署、云 Provider 适配和灾备。

## 五、正式发布门禁

以下全部通过后才能把完整 MVP 标记为“可上线”：

- [ ] 四个模板至少各完成一次真实模型生成。
- [ ] 客户选片流程完成真实浏览器 E2E。
- [ ] 确认前源码目录必须为空或不存在。
- [ ] 刷新页面能恢复等待确认、暂停和执行中的 Job。
- [ ] 暂停 + Worker 重启 + 继续能成功。
- [ ] `docker compose up --build` 全栈启动。
- [ ] MySQL 8.4 空库执行 V1～V17。
- [ ] Browser Worker 网络绕过和破坏性探针被拒绝。
- [ ] Runtime Token Scope、ownership 和跨租户测试全部通过。
- [ ] 发布 URL 原地更新、回滚、取消发布真实通过。
- [ ] 未实现工具不再出现在 Planner 契约中。
- [ ] 审计、用量和成本口径与实际数据一致。

## 六、建议顺序

1. 修复工具契约并补 Job 恢复 API/前端恢复。
2. 完成 Runtime Scope、ownership 和 Schema 兼容校验。
3. 完成真实模型四模板 E2E。
4. 完成 Docker Compose、MySQL 8.4 和容器隔离验收。
5. 完成 Preview/发布/回滚浏览器 E2E。
6. 补模板 Golden Source、Reviewer 安全业务测试。
7. 最后再做函数运行时、素材处理、社区和大规模压测。
