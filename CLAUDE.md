# 小玉零代码平台（xiaoyu_zerocode）

零代码应用生成平台。后端 Spring Boot 3.5 + Spring AI（DeepSeek 兼容接口）+ MyBatis-Flex + Redis(Session) + MySQL，端口 8124，context-path `/api`；前端 Vue 3 + Vite + TS + Ant Design Vue，位于 `xiaoyuZeroCode-front`（独立 npm 工程）。

## 代码规范（写代码前必读）

遵循 [code_rule.md](code_rule.md)：入参校验（`ThrowUtils.throwIf`）、建线程优先虚拟线程、并发要考虑共享资源竞争、MyBatis-Flex 操作符用枚举、雪花 ID 全程按字符串、前端 antd 分页字段要 `Number()` 等。

## 问题记录

踩过的坑与排查记录见 [development-notes.md](development-notes.md)（编号条目，最新到 #63）。

## 构建与运行

- **后端编译/测试**：JDK 21 在 `C:\Users\莫凡毅\.jdks\corretto-21.0.10`，Maven 在 `C:\Users\莫凡毅\.m2\wrapper\dists\apache-maven-3.9.14\...\bin\mvn`（均不在 PATH）。先 `export JAVA_HOME="$HOME/.jdks/corretto-21.0.10"`，依赖已缓存可离线编译。
- **后端启动**：端口 8124，接口前缀 `/api`。
- **前端**：`cd xiaoyuZeroCode-front && npm run dev`（Vite）。
- **openapi2ts**：跑完 `npm run openapi2ts` 必须检查 `src/api/` 手写文件是否被删（如 `aiStream.ts`），被删则 `git checkout --` 恢复。
