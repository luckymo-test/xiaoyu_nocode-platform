# 小玉零代码平台 · 代码规范

> 由 `.claude` 记忆中的问题与约定统一整理而来。写代码时优先遵守本文件约定；
> 与 `development-notes.md` 的问题记录配套使用，本文件聚焦"怎么写"，不重复记录问题排查过程。

## 一、后端规范

### 1. 入参校验（每个方法必须）

所有方法（Controller / Service / 工具类）入参都要用 `ThrowUtils.throwIf` 校验，方法开头先写入参校验再写业务逻辑。

```java
ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
ThrowUtils.throwIf(StrUtil.isBlank(x), ErrorCode.PARAMS_ERROR, "xx 不能为空");
ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
```

覆盖范围：
- Controller 层：`@RequestBody` DTO 为 null、`id <= 0`、必填字段为空
- Service 层：入参 null、`loginUser` 为空、`id <= 0`、字符串空白
- 工具类/门面：对象为 null、`appId`/id 非法、字符串空白

> 业务内层已有统一校验的可不在外层 Controller 重复，但 Service/工具类公开方法必须各自校验。

### 2. 并发与线程

- **建线程优先用 JDK 21 虚拟线程**，不要 `new Thread(...)`。虚拟线程创建成本极低、不占平台线程，适合短生命周期阻塞型任务（读进程 stdout/stderr、IO 等待）；`join(3000)` 超时语义不变：
  ```java
  Thread t = Thread.ofVirtual().name("task").start(() -> doSomething());
  t.join(3000);
  ```
- **有"复用/限流"需求才用 Spring 管理的 `ThreadPoolExecutor` Bean**（`thread-pool.vue` 可配）。注意两个坑：
  - 拒绝策略是 CallerRunsPolicy 时会回退到调用线程执行（可能阻塞调用方）；
  - 池线程被长阻塞 I/O（如 `readLine()` 等进程输出）占满时会**饿死排队任务**——不要把读进程输出这类长阻塞任务塞进小池子。
- **并发场景主动检查共享资源竞争：**
  - 同一文件/目录并发写会互相覆盖（如同一 appId 的 `dist`/`node_modules`），要按资源加锁或信号量限制并发；
  - 非线程安全容器（`StringBuilder`/`ArrayList` 等）跨线程共享，必须保证 happens-before（`join`/`Future.get`/同步器）后再读，否则有数据竞争；
  - 超时/异常路径要收尾：杀进程后要 join 读线程或确保流被关闭，避免后台线程残留。

### 3. MyBatis-Flex 操作符必须用枚举

`QueryCondition.create` 传字符串操作符（`"lt"`/`"eq"`）会被**原样拼进 SQL**，不会映射成 `<`/`=`，产生非法 SQL 导致 50000 系统错误。必须用枚举：

```java
// ❌ QueryCondition.create(new QueryColumn("col"), "lt", value)
// ✅ QueryCondition.create(new QueryColumn("col"), SqlOperator.LT, value)
```

> 排查其他 50000 时优先检查 QueryCondition 操作符写法。

### 4. 雪花 ID 精度

后端 `JsonConfig` 已把 `Long` 序列化为**字符串**（`ToStringSerializer`），反序列化也接受字符串 body。雪花 ID 前后端全程按字符串传递，不要转数字。

### 5. Agent 工具注册：工厂式动态绑定；无状态工具才用 Spring AI `@Tool`

Spring AI 1.1.8 同时支持两种工具声明方式，按工具形态取舍：

- **工具需要"每次生成的运行时上下文"（type/appId/进度 listener/状态 ref 等，非模型参数）时，用工厂式**：每个工具一个 `@Component`，暴露 `build(<上下文>) → ToolCallback`，由 `ai/VueProjectAgent.runToolLoop` 把各 `build(...)` 结果并成 `List.of(...)` 交给 ChatOptions。见 `ai/tools/`（WriteFileTool/ReadFileTool/SearchImageTool/FinishTool）。
  - 理由：writeFile 写哪个工程、searchImage 归属哪个 app、finish 落到哪个 `AtomicReference`，都是服务端按一次生成绑定的，模型 JSON 填不出来；若硬用 `@Tool` 就得把上下文塞 ThreadLocal/请求级容器，而生成跑在线程池 + 工具由框架在 stream 内部（可能跨 reactor 线程）执行，ThreadLocal 易错绑/残留，反而不安全。
  - 纯"能力/通用逻辑"不与具体生成绑定的，下沉到 `core`（如 `SiteImageService`、`ToolPaths`），供工具与 agent 后处理复用。
- **工具无状态、入参全部由模型提供时，才用 `@Tool` + `MethodToolCallbackProvider`**：把 `@Tool` 方法写在 `@Component` bean 上，参数用 `@ToolParam`。它产出的同样是 `ToolCallback`，可直接并进上面的 `List.of(...)`，两种方式可共存。当前没有这类工具，未来新增"查询类/通用能力"工具时优先走 `@Tool`。

> 补充：工具由框架**自动执行**（toolCallbacks 注册进 ChatOptions），不要写"手动分发循环"再执行一遍，避免双份维护（历史踩过 executeTool 死代码）。

## 二、前端规范

### 1. 雪花 ID 全程按字符串

后端 Long 雪花 ID 超过 JS 安全整数（如 `443253985292632064`），前端**必须全程按字符串传递应用 id**：

- `route.params.id` 不要 `Number()`，直接作为 string 传给接口；
- JSON body 里传 `"id":"443253985292632064"` 后端正常接受；
- 切忌 `Number(route.params.id)`，否则雪花 ID 丢精度导致查不到数据（40400）。

### 2. 后端字符串数字字段赋给 antd 前要 `Number()`

后端 `totalRow`/`pageNum` 等字段返回的是**字符串**，赋给 antd `pagination.total` 前要先 `Number()`，否则分页失效。

### 3. 跑 `npm run openapi2ts` 后检查手写文件

openapi2ts 按后端 swagger 重新生成 `src/api/`，会**删除手写的非生成文件**（如 `aiStream.ts`，AppGeneratePage 依赖的 SSE 封装）。每次跑完用 `git status src/api/` 检查，看到手写文件被删（标记 `D`）就 `git checkout -- <文件>` 恢复。

## 三、工具与环境注意

### 1. 本机 Maven / JDK 不在 PATH

- JDK 21：`C:\Users\莫凡毅\.jdks\corretto-21.0.10`（IDEA 项目 jdk.name=corretto-21）
- Maven：`C:\Users\莫凡毅\.m2\wrapper\dists\apache-maven-3.9.14\...\bin\mvn`（IDEA Maven wrapper 缓存，项目内无 mvnw）
- 运行 Maven 前先 `export JAVA_HOME="$HOME/.jdks/corretto-21.0.10"`，依赖已缓存到 `.m2`，可离线编译。

### 2. curl 发中文 JSON 用 UTF-8 文件传 body

Windows Git Bash 里 `curl -d '{"key":"中文"}'` 会被终端按 **GBK** 编码发送，服务端按 UTF-8 解析失败，报 `Invalid UTF-8` 被兜底成 50000。**这是测试命令的编码问题，不是业务 bug。**

用 UTF-8 文件传 body，绕开终端编码转换：

```bash
cat > /tmp/body.json <<'EOF'
{"appName":"我的登录页",...}
EOF
curl -X POST "http://localhost:8124/api/app/add" -H "Content-Type: application/json" -b cookies.txt -d @/tmp/body.json
```

关键：`<<'EOF'`（单引号定界符）写文件不经过终端编码转换；`-d @file` 原样发字节。登录态用 `-c cookies.txt` 存、`-b cookies.txt` 发。
