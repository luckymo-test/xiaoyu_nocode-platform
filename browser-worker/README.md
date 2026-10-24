# Playwright Browser Worker

Phase D 的独立浏览器执行进程。Java 只通过 HTTP 提交当前工作区和三档视口，
Node Worker 使用 Playwright 执行检查并写回结构化报告、截图和 trace。

## 安装与启动

```powershell
cd browser-worker
npm install
$env:BROWSER_WORKER_WORKSPACE_ROOT = 'D:\IDEAproject\xiaoyu_zerocode\tmp'
$env:BROWSER_WORKER_CHANNEL = 'msedge'
npm start
```

默认监听 `127.0.0.1:8090`，健康端点：

```text
GET http://127.0.0.1:8090/health
```

如果 Edge 不在 Playwright channel 搜索路径，设置：

```powershell
$env:BROWSER_WORKER_EXECUTABLE = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
```

## Java Adapter

```yaml
codegen:
  browser:
    engine: playwright
    playwright:
      url: http://127.0.0.1:8090
      connect-timeout-ms: 3000
      read-timeout-ms: 120000
```

Worker 只接受位于 `BROWSER_WORKER_WORKSPACE_ROOT` 下的 `sourceDir/outputDir`，
不接受 URL、宿主机任意路径或数据库连接。
