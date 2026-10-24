# Build / Browser Sandbox 安全边界

> 日期：2026-10-24
> 状态：代码级隔离与配额完成；容器镜像、编排和破坏性探针已准备，
> 内核级隔离仍需在 Linux 容器环境执行验收

## 一、当前隔离模型

```text
Generation Worker
  ├── 当前 App 的独立临时构建目录
  ├── 依赖 / build script allowlist
  ├── 有界日志、超时、进程树回收
  └── 文件数量 / 总大小 / 符号链接守卫

Browser Node Worker
  ├── 仅允许访问指定工作区
  ├── egress host allowlist
  ├── 单次最大时长和强制浏览器关闭
  └── 报告、截图与 trace 配额
```

当前机器没有 Docker、WSL 或 Podman 运行时，因此本轮不伪造“容器已启用”。
代码级守卫先保证 fail-closed；正式生产仍应放入容器或轻量 VM。

`sandbox-runtime/` 已提供 Browser Worker 与 Generation Worker 镜像、只读 rootfs、
capability drop、PID/CPU/内存限额、内部网络、Squid 域名 allowlist 和
`probe-isolation.sh`。这些文件只有在真实 Linux 容器中跑通后才能标记生产隔离完成。

## 二、Build Sandbox

### 目录

- 不再直接在 live 工程执行 npm；
- 每次构建复制到独立 `tmp/sandbox/builds/{appId}-{uuid}`；
- 修复 Agent 修改 live 后，重新同步源码但保留 `node_modules`；
- 构建结束、失败、超时都会清理临时目录；
- 成功后才把 `dist` 替换回 live 工程。

### 包与脚本

- 强制存在 `package.json` 与 `package-lock.json`；
- `dependencies/devDependencies` 必须命中 allowlist；
- 当前允许：Vue/Vite/plugin-vue/TypeScript/Vue-TSC/Pinia/Vue Router/Axios/GSAP；
- `build` 脚本只允许固定 Vite 构建命令；
- 默认 `--ignore-scripts`；
- npm registry 与 cache 都由服务端配置，不接受模型任意 URL。

### 配额与进程

- 源码默认最多 2,000 文件 / 50 MB；
- 构建产物默认最多 10,000 文件 / 200 MB；
- 拒绝任何符号链接；
- 单条命令默认超时 300 秒；
- stdout/stderr 各限制 200,000 字符；
- 超时会递归销毁进程树并 wait/回收；
- 环境变量使用 fail-closed 白名单，只保留 Node/npm 正常运行所需的系统变量；
- npm 使用沙箱内空 `.npmrc`、独立 HOME、cache、TEMP/TMP/TMPDIR；
- `.agent/build-report.json` 记录成功、耗时、原因、源码/产物文件数和大小。

## 三、Browser Sandbox

- `sourceDir/outputDir` 必须是 `BROWSER_WORKER_WORKSPACE_ROOT` 的真实子路径；
- 静态服务器与目录统计都解析 symlink，禁止逃逸；
- 默认阻止 allowlist 外 HTTP/HTTPS 请求；
- 云元数据地址、内网地址、非审核 CDN 会产生 `EGRESS_BLOCKED`；
- 默认 allowlist：
  - 本地静态源；
  - 腾讯 COS 图片域名；
  - Pexels/Pixabay CDN；
  - Google Fonts；
- 单次验证硬超时 30～120 秒，超时强制关闭浏览器；
- 源码/输出文件数与大小沿用 SandboxProperties；
- 报告记录 duration、source/output stats、Node RSS/heap、timeout 标记。

## 四、配置

```yaml
codegen:
  sandbox:
    enabled: true
    max-source-files: 2000
    max-source-bytes: 52428800
    max-output-files: 10000
    max-output-bytes: 209715200
    command-timeout-seconds: 300
    build:
      require-lockfile: true
      ignore-scripts: true
      registry: https://registry.npmmirror.com
    egress:
      allowed-hosts:
        - 127.0.0.1
        - localhost
        - yun-picture-1419359718.cos.ap-shanghai.myqcloud.com
        - images.pexels.com
        - cdn.pixabay.com
        - fonts.googleapis.com
        - fonts.gstatic.com
```

## 五、已验证

- 未知依赖和任意 shell build script 被拒绝；
- 缺少 lockfile 被拒绝；
- 文件数配额超限被拒绝；
- 真实 Vue 工程完成隔离目录 npm install/build，成功后回写 dist；
- 构建报告生成，临时沙箱清理；
- Playwright 对 `169.254.169.254` 云元数据请求产出 `EGRESS_BLOCKED`；
- HTTP 与 Redis Streams 两种 Browser Worker 传输继续通过真实探针。

## 六、仍需容器运行时

代码级守卫不能提供内核级保证：

- fork bomb 可在超时前短暂创建大量进程；
- npm install script 即使默认禁用，原生依赖仍值得在容器中运行；
- egress route 只覆盖浏览器页面请求，不能约束任意原生进程；
- CPU、内存 cgroup、PID namespace 和只读 rootfs 仍未实现。

因此生产部署必须继续完成：

- 在 Docker/Podman 主机构建并记录镜像 digest；
- 用只读 rootfs、CPU/内存/PID/临时盘 cgroup 启动 Generation/Browser Worker；
- 验证 Browser Worker 只能通过 Squid 出站，不能绕过代理直连公网；
- 验证 npm registry proxy 与依赖缓存策略；
- 执行 fork bomb、OOM、无限循环和超时后强制销毁测试。

入口与验收命令见 [sandbox-runtime/README.md](../sandbox-runtime/README.md)。
