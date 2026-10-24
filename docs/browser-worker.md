# Playwright Browser Worker

> 日期：2026-10-24
> 状态：Phase D 完成；HTML / MultiFile / Vue 构建产物均已完成三档真实验证

## 一、边界

```text
Java BrowserVerifierPort
  ├── SeleniumBrowserVerifierAdapter（默认回退）
  └── PlaywrightHttpAdapter
          │ POST /verify
          ▼
      Node Playwright Worker
          ├── 临时 HTTP 静态源
          ├── Chromium / Edge
          ├── 三档视口截图
          ├── console / network / DOM 采集
          └── Playwright trace
```

Playwright Worker 不连接 MySQL，只接收一个位于允许根目录内的工作区和输出目录，
返回结构化报告并生成 Artifact 文件。Java Adapter 支持：

- `transport=http`：同步 HTTP，默认用于本地回退；
- `transport=redis`：Redis Streams 下发任务、Worker 回写结果键，Generation Worker
  默认使用这一模式。

## 二、选择引擎

```yaml
codegen:
  browser:
    engine: selenium  # selenium / playwright
    playwright:
      url: http://127.0.0.1:8090
      transport: http  # http / redis
      connect-timeout-ms: 3000
      read-timeout-ms: 120000
      redis:
        stream: codegen:browser-verify:requests
        result-prefix: codegen:browser-verify:result:
        poll-millis: 200
```

- `selenium` 是默认值，保留本地开发和应急回退。
- `playwright` 使用独立 Node Worker。
- `Generation Worker` 只允许 `playwright`；如果开启验证却配置成 Selenium，启动直接失败。
- `Control API` 不装配 `BrowserVerifierPort`。

## 三、启动 Node Worker

```powershell
cd browser-worker
npm install
$env:BROWSER_WORKER_WORKSPACE_ROOT = 'D:\IDEAproject\xiaoyu_zerocode\tmp'
$env:BROWSER_WORKER_CHANNEL = 'msedge'
npm start
```

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8090/health
```

支持配置：

```text
BROWSER_WORKER_PORT
BROWSER_WORKER_BIND_ADDRESS
BROWSER_WORKER_WORKSPACE_ROOT
BROWSER_WORKER_CHANNEL
BROWSER_WORKER_EXECUTABLE
BROWSER_WORKER_SETTLE_MILLIS
BROWSER_WORKER_MAX_CONCURRENCY
BROWSER_WORKER_REQUIRE_CONTAINER
BROWSER_WORKER_REQUIRE_PROXY
BROWSER_WORKER_PROXY_SERVER
BROWSER_WORKER_PROXY_BYPASS
BROWSER_WORKER_REDIS_URL
BROWSER_WORKER_REDIS_STREAM
BROWSER_WORKER_REDIS_GROUP
BROWSER_WORKER_REDIS_CONSUMER
BROWSER_WORKER_REDIS_RESULT_PREFIX
BROWSER_WORKER_REDIS_RESULT_TTL_SECONDS
```

## 四、验证内容

每个视口会检查：

- 可见图片 `complete && naturalWidth === 0` 的图裂；
- `scrollWidth > clientWidth + 1` 的横向溢出；
- body 可见文本少于 20 字符的疑似空白页；
- console error 与 pageerror；
- request failed、HTTP 4xx/5xx；
- console/network 文本中的 CORS 信号；
- 视口截图及 SHA-256。

页面由 Worker 启动的一次性 `127.0.0.1` 静态服务器提供，避免 `file://`
对 ES module、fetch 和相对路径造成系统性误报。

## 五、输出 Artifact

工作区 `.agent` 目录写入：

```text
verify-{stage}.json
network-{stage}.json
console-{stage}.json
network-failures.json
console-errors.json
screenshots/{stage}-desktop.png
screenshots/{stage}-tablet.png
screenshots/{stage}-mobile.png
trace-{stage}.zip
```

Revision 发布时登记：

- `RENDER_REPORT_BEFORE / AFTER`
- `NETWORK_FAILURE_BEFORE / AFTER`
- `CONSOLE_ERROR_BEFORE / AFTER`
- `VIEWPORT_SCREENSHOT`
- `BROWSER_TRACE_BEFORE / AFTER`
- `VISUAL_DIFF`

## 六、安全边界

当前版本已经实现：

- 只监听 `127.0.0.1`；
- 容器内可显式监听 `0.0.0.0`；非容器进程拒绝非回环监听；
- 可启用容器/代理启动门禁，缺少任一生产条件时 fail-closed；
- HTTP 与 Redis 共享受限并发闸门，超限 HTTP 请求返回 429；
- 请求体上限 1 MB；
- `sourceDir/outputDir` 必须位于 `BROWSER_WORKER_WORKSPACE_ROOT`；
- 只接受带 `index.html` 的明确工作区；
- 不接受 URL、数据库配置或任意命令；
- 单次验证最多 5 个视口，报告事件有数量上限。

以下属于 Phase E 的部署验收：

- 容器/低权限用户的真实镜像构建与启动；
- Squid egress allowlist 与禁止直连公网；
- CPU、内存、进程数和临时盘 cgroup 限额；
- 强超时后的整容器回收；
- fork bomb、OOM 和无限循环破坏性探针。

镜像、Compose 和探针入口见 [sandbox-runtime/README.md](../sandbox-runtime/README.md)。

Redis Streams 消费组支持多 Worker 竞争消费；超过 60 秒的 pending 消息会通过
`XAUTOCLAIM` 接管。Redis 6.2 以下不支持该命令时，Worker 自动切换到
`XPENDING + XCLAIM`，不会因单次命令不支持而阻塞新消息消费。结果键按
verificationId 覆盖并由 Java 端删除。

## 七、真实探针结果

- Node Worker 启动成功，Edge channel 可用；
- 问题页面在 desktop / tablet / mobile 三档均检出图裂与横向溢出；
- 三张截图、`network-before.json`、`console-before.json`、`trace-before.zip` 均生成；
- Java `PlaywrightBrowserVerifier` 通过 HTTP 调用真实 Worker，映射为统一
  `RenderReport`，三档全部可用并检出问题；
- Java `RedisBrowserVerificationTransport` 经 Redis Streams 下发任务，Node Worker
  消费并回写结果键，端到端探针通过；
- Redis 5 上真实验证 XAUTOCLAIM 不可用后自动切换 `XPENDING + XCLAIM`，
  新任务继续被消费且三档问题页正常返回；
- 普通 PDF/截图文本不作为生成质量判断依据，真实 DOM/console/network 才是报告事实。

## 八、Vue 验证

Vue 生成完成后：

1. `VueBuildService` 在隔离目录执行 npm install/build；
2. 成功后将 `dist` 替换回 live 工程；
3. Playwright Adapter 自动把 `dist` 作为静态源；
4. 对构建产物执行 desktop/tablet/mobile 三档验证。

真实 Vue 构建产物探针已完成三档验证。React/Next 仍属于 Phase F。
