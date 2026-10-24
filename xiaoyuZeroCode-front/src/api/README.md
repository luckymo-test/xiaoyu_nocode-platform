# 手写 API

本目录包含 SSE、CSRF、可视编辑、运行时选择等无法由普通 OpenAPI Client 完整表达的接口。

OpenAPI 生成代码统一输出到 `src/generated/api`，执行 `npm run openapi2ts` 不得删除或覆盖
本目录中的手写文件。生成器完成后会自动执行 `scripts/check-openapi-client.mjs`。
