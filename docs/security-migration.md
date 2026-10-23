# Spring Security 灰度与回滚手册

> 验证日期：2026-10-24  
> 当前默认：Spring Security 与 CSRF 均关闭，继续使用原 Session/AOP 路径  
> 目标：浏览器控制台继续使用 HttpOnly Cookie Session，不改为浏览器 JWT

## 一、Feature Flag

```yaml
codegen:
  security:
    spring-security:
      enabled: false
    csrf:
      enabled: false
    cors:
      allowed-origins:
        - http://localhost:5199
        - http://127.0.0.1:5199
```

环境变量：

```text
CODEGEN_SPRING_SECURITY_ENABLED
CODEGEN_CSRF_ENABLED
CODEGEN_CORS_ORIGIN_LOCAL
CODEGEN_CORS_ORIGIN_IP
SESSION_COOKIE_SECURE
```

推荐灰度顺序：

```text
Security=true, CSRF=false
→ 验证登录、SSE、版本、团队与管理员接口
→ CSRF=true
→ 验证 SPA 的 CSRF 引导与所有写请求
→ 稳定后移除旧 @AuthCheck AOP
```

## 二、认证与授权

- `SessionAuthenticationFilter` 从现有 `user_login` Session 属性恢复当前用户。
- 用户角色映射为 `ROLE_ADMIN` / `ROLE_USER`，不复制一套新的账号体系。
- `@AuthCheck` 暂保留作为灰度回退；Spring Security 开启时，
  管理员接口同时有 `@PreAuthorize("hasRole('ADMIN')")`。
- 登录成功后轮换 Session ID，防止 Session Fixation。
- 登出删除登录属性并使 Session 失效，避免安全上下文残留在同一 Session。
- 未登录返回 HTTP 401，响应体 `code=40100`；无权限返回 HTTP 403，
  响应体 `code=40101`，保持前端统一错误格式。

匿名可访问：

```text
POST /user/login
POST /user/register
POST /v1/analytics/events
GET  /health/
GET  /v1/security/csrf
GET  /v1/templates
GET  /v1/stack-profiles
GET  /v1/skills
GET  /v1/evaluation/prompts
GET  /v1/community/publications
GET  /v1/community/publications/**
```

其他接口默认要求登录。Workspace、App 归属和团队角色仍由业务层 ACL 再次校验，
Spring Security 不能替代租户权限判断。

## 三、CSRF

- Cookie：`XSRF-TOKEN`，允许 JavaScript 读取。
- 请求头：`X-XSRF-TOKEN`。
- 引导接口：`GET /v1/security/csrf`。
- 前端在首次写请求前自动调用引导接口；获得 Cookie 后，Axios 和手写 `fetch`
  都附带请求头。
- `/v1/analytics/events` 是部署后应用上报的匿名接口，不使用控制台 CSRF Token；
  继续使用现有 Redis 速率限制和字段截断。

实际冒烟结果：

- 引导接口返回 200 并写入 `XSRF-TOKEN`。
- 缺少 CSRF 的登录请求返回 403 / `40101`。
- 带 CSRF 的注册、登录、登出均返回 200。
- 登出后受保护接口返回 401 / `40100`。
- 登出后再次登录会生成不同的 Session ID。

## 四、CORS 与 Cookie

- CORS 不再使用 `allowedOriginPatterns("*") + credentials`。
- 只允许配置 allowlist 中的 Origin，方法、请求头和缓存时间均显式声明。
- 部署到独立前端域名时，必须通过配置加入准确 Origin，不能用通配符代替。
- Session Cookie 使用 `HttpOnly`、`SameSite=Lax`。
- HTTPS 部署必须设置 `SESSION_COOKIE_SECURE=true`；本地 HTTP 默认 false。

## 五、已有行为与已知边界

- 默认两个 Feature Flag 都关闭，因此上线代码不会立即改变现有认证路径。
- CORS 白名单和 Session 固定攻击防护在默认路径下也生效，这是安全修复。
- `@AuthCheck` AOP 与 `@PreAuthorize` 当前短暂共存，便于开关切换；
  Spring Security 稳定后删除 AOP 和重复注解，避免两次数据库用户查询。
- API Token / OAuth 属于后续外部开放 API 通道，不与浏览器 Session 混用。
- 公开事件接口已有单 App 每分钟 1000 条上限；若扩展公网匿名入口，
  还需在网关增加 IP/租户维度的全局限流。

## 六、回滚

1. 将 `CODEGEN_SPRING_SECURITY_ENABLED=false`。
2. 重启应用，确认旧 `@AuthCheck` 路径和现有 Session 登录态可用。
3. 若仅 CSRF 阻断写请求，可先将 `CODEGEN_CSRF_ENABLED=false`，保留认证链。
4. 回滚不会修改数据库表结构，不需要执行数据库迁移。
5. 回滚后保留失败请求日志和 SPA 版本号，定位是 Origin、CSRF Cookie、
   Session 过期还是权限映射问题。
