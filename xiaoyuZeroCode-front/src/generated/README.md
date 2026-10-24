# 生成代码

`api/` 由 `npm run openapi2ts` 根据后端 `/v3/api-docs` 生成，类型命名空间为
`GeneratedAPI`。不要在这里手写业务封装；需要 SSE、CSRF 或特殊重试逻辑时放到
`src/api/`。
