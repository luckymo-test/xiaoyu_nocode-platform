# Playwright Browser Worker

> 日期：2026-10-24
> 状态：Phase D 执行框架完成；HTML / MultiFile 已验证，Vue 源码构建仍需 Execution Build Worker

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
返回结构化报告并生成 Artifact 文件。当前 Java Adapter 使用同步 HTTP 调用；
基于 Outbox/Redis Streams 的异步验证任务与结果回调仍属于后续增强。

## 二、选择引擎

```yaml
codegen:
  browser:
    engine: selenium  # selenium / playwright
    playwright:
      url: http://127.0.0.1:8090
      connect-timeout-ms: 3000
      read-timeout-ms: 120000
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
BROWSER_WORKER_WORKSPACE_ROOT
BROWSER_WORKER_CHANNEL
BROWSER_WORKER_EXECUTABLE
BROWSER_WORKER_SETTLE_MILLIS
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
- 请求体上限 1 MB；
- `sourceDir/outputDir` 必须位于 `BROWSER_WORKER_WORKSPACE_ROOT`；
- 只接受带 `index.html` 的明确工作区；
- 不接受 URL、数据库配置或任意命令；
- 单次验证最多 5 个视口，报告事件有数量上限。

以下仍属于 Phase E：

- 容器/低权限用户运行；
- 网络 egress allowlist；
- CPU、内存、进程数和临时盘配额；
- 依赖与 npm 构建隔离；
- 强超时后的进程树回收。

以下属于 Phase D 后续增强：

- 将同步 HTTP 请求升级为 Outbox/Redis Streams 异步任务和结果回调；
- Worker 多实例消费、任务租约与跨实例结果去重。

## 七、真实探针结果

- Node Worker 启动成功，Edge channel 可用；
- 问题页面在 desktop / tablet / mobile 三档均检出图裂与横向溢出；
- 三张截图、`network-before.json`、`console-before.json`、`trace-before.zip` 均生成；
- Java `PlaywrightBrowserVerifier` 通过 HTTP 调用真实 Worker，映射为统一
  `RenderReport`，三档全部可用并检出问题；
- 普通 PDF/截图文本不作为生成质量判断依据，真实 DOM/console/network 才是报告事实。

## 八、Vue 说明

Playwright 已能验证带有效 `index.html` 的静态页面和构建产物，但当前 Vue 源码工程
没有 npm build/Execution Worker，因此不能把尚未构建的 `.vue` / TypeScript 源码
当成可运行页面。Vue 的三档验证将在 Phase F 的 StackProfile 构建链路接通后启用，
当前不得将 Vue 标记为已验证。
