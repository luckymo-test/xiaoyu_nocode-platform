# Browser Sandbox 容器运行层

该目录把 Playwright Browser Worker 放入低权限容器，并提供可重复的资源与网络隔离探针。
它补的是 Phase E 的部署边界，不替代 Java Build Sandbox 的依赖 allowlist 和文件配额。

## 启动条件

- Linux Docker Engine 24+，Docker Compose v2；
- 2 CPU、2.5 GiB 可用内存；
- 宿主工作区必须是 Browser Worker 唯一可写挂载，禁止挂载 Docker socket、源码仓库或用户主目录；
- 镜像需从可信 registry 拉取并记录 digest。

当前开发机没有 Docker/WSL/Podman，因此容器镜像构建、破坏性探针和网络拒绝测试尚未在本机执行。

同一目录还提供 Java Generation Worker 镜像。它使用 Node + JRE 21，以 UID 10001
运行，并把 npm 构建限制在 `/workspace`。生产环境必须同时给该容器设置只读 rootfs、
tmpfs `/tmp`、PID/CPU/内存限额和仅连接内部后端网络；不要挂载 Docker socket。

```bash
docker build -f sandbox-runtime/Dockerfile.generation-worker \
  -t xiaoyu/generation-worker:1.0.0 .
```

Generation Worker 启动参数仍使用：

```text
--spring.profiles.active=location,worker
--codegen.jobs.execution-mode=worker
--codegen.sandbox.require-container=true
```

镜像默认设置 `CODEGEN_BROWSER_ENGINE=playwright` 和
`CODEGEN_PLAYWRIGHT_TRANSPORT=redis`。真实连接信息必须通过部署环境注入，
不得写进镜像。

## 启动

```bash
cp sandbox-runtime/env.example sandbox-runtime/.env
# 修改 SANDBOX_WORKSPACE_ROOT 为 Generation Worker 与 Browser Worker 共享的绝对路径
docker compose --env-file sandbox-runtime/.env \
  -f sandbox-runtime/compose.yaml up --build
```

Browser Worker 不发布宿主端口，只加入 `sandbox-internal` 网络。该网络没有默认网关；
浏览器外网请求必须经过 Squid。Squid 只允许：

- 腾讯云图片 COS 域名；
- Pexels / Pixabay 图片 CDN；
- Google Fonts。

本地 `127.0.0.1` 静态源通过 Playwright proxy bypass 直连。

## 强制边界

- `BROWSER_WORKER_REQUIRE_CONTAINER=true`：检测不到容器标记时拒绝启动；
- `BROWSER_WORKER_REQUIRE_PROXY=true`：没有代理时拒绝启动；
- `BROWSER_WORKER_BIND_ADDRESS=0.0.0.0` 仅允许在容器内使用；
- 容器根文件系统只读，`/tmp` 为有界 tmpfs；
- 删除全部 Linux capabilities，启用 `no-new-privileges`；
- `pids_limit=256`、`mem_limit=2g`、`cpus=1.5`、共享内存 512 MiB；
- Browser Worker 进程以 UID/GID 1000 运行；
- HTTP 与 Redis 验证共享并发闸门，默认最多 1 个 Chromium 任务；
- 页面 egress allowlist 仍保留，作为 Squid 之外的第二层检查。

## 破坏性探针

在 Linux 容器主机执行：

```bash
SANDBOX_WORKSPACE_ROOT=/absolute/path/to/shared/tmp \
  sh sandbox-runtime/probe-isolation.sh
```

探针检查只读 rootfs、非 root 用户、CPU/内存/PID 限额、禁止直连公网、超时终止、
fork bomb 受限和健康端点隔离标记。探针会启动隔离网络与临时 Redis，结束后自动删除。

探针通过只说明该主机和镜像的容器边界成立；发布前仍需记录镜像 digest、内核版本和
失败时的 OOM/超时状态。
