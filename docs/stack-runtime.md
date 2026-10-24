# StackProfile 运行时

> 日期：2026-10-24
> 状态：`react-vite-ts` 完成隔离构建与三档 Playwright 闭环；`next-static-export` 尚未启用

## 一、职责

`CodeGenTypeEnum.VUE` 只保留为文件目录、数据库和旧 API 的兼容类型。
真正的技术栈由 `StackProfile` 决定：

- `vue-vite`：Vue 3 + Vite + JavaScript；
- `react-vite-ts`：React + Vite + TypeScript；
- `next-static-export`：暂未实现。

Job 创建时固化 `stackProfileId + version`。Worker 执行期间通过 `StackProfileContext`
把同一 Profile 传给 Prompt、构建和构建修复，避免各层再次猜测运行时。

## 二、启用 React

```text
CODEGEN_RUNTIME_REACT_VITE_TS_ENABLED=true
```

默认关闭。控制台通过 `GET /v1/runtime-profiles` 获取能力目录和 Feature Flag 状态，
只有 `available=true` 的 Profile 才会出现在生成模式下拉框中。

生成请求仍使用兼容类型 `codeGenType=vue`，并显式提交：

```json
{
  "codeGenType": "vue",
  "stackProfileId": "react-vite-ts"
}
```

## 三、React 工程边界

- 最小结构：`package.json`、`package-lock.json`、`vite.config.ts`、
  `tsconfig.json`、`src/main.tsx`、`src/App.tsx`；
- build 脚本只允许 `vite build`、`tsc --noEmit && vite build` 或
  `tsc -b && vite build`；
- 依赖必须命中 React Profile allowlist；
- `tsconfig.strict=true`，修复提示词禁止用 `any`、`@ts-ignore` 或关闭类型检查绕过；
- 构建产物为 `dist`，Vue/React 共用 Revision、Artifact 和 Playwright 验证路径。

## 四、npm 启动器

默认使用配置的 npm。离线环境可显式指定：

```yaml
codegen:
  node:
    use-explicit-cli: true
    command: /path/to/node
    npm-cli: /path/to/npm-cli.js
```

如果宿主 npm 与 Node 版本不匹配，可使用 Corepack：

```text
CODEGEN_NODE_COREPACK_COMMAND=corepack
```

Codex Corepack home 固定在当前构建沙箱内，不读取宿主 npm 用户配置。

## 五、验证

```powershell
$env:JAVA_HOME = 'D:\computer tools\java\JDK'
& 'D:\computer tools\IDEA\IntelliJ IDEA 2023.3.2\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -o -DcodegenProbe=true -Dtest=ReactViteBuildSandboxProbeTest test
```

浏览器闭环需先启动 `browser-worker`，再运行：

```text
-Dtest=ReactViteBuiltDistPlaywrightProbeTest
```

2026-10-24 验证结果：

- React fixture 在隔离目录完成 `npm ci + tsc --noEmit + vite build`；
- `build-report.json` 记录 `stackProfileId=react-vite-ts`；
- dist 在 desktop / tablet / mobile 三档全部通过；
- 前端控制台构建通过，React Profile 只在 Feature Flag 开启后展示。

## 六、剩余

- `next-static-export` 的 scaffold、Prompt、allowlist、`out` 验证和 Feature Flag；
- 固定评估集比较 Vue、React、Next 的 token、耗时、通过率和人工评分；
- 只有通过质量门禁后才允许把对应 Profile 默认开启。
