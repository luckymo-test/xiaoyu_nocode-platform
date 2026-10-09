# 开发问题与解决方案记录

> 小玉零代码平台（xiaoyu_zerocode）开发过程中遇到的问题与解决方案。
> 按主题分类，每条包含：现象 → 原因 → 解决。
> 第一~六章为后端（Spring Boot + Spring AI + MyBatis-Flex），第七章为前端（Vue 3 + Ant Design Vue），第八章为对话记忆与历史（Redis + MySQL 双写），第九章为 Vue3 工程生成模式。

---

## 一、环境与工具链

### 1. Maven / JDK 不在 PATH

**现象**：命令行执行 `mvn` / `java` 提示 command not found，或 `java -version` 显示 1.8（版本错误）。

**原因**：本机 JDK 21 在 `~/.jdks/corretto-21.0.10`，Maven 缓存在 `.m2/wrapper/dists/apache-maven-3.9.14/...`，都不在 PATH；系统 `java` 指向的是旧版 JDK 8。

**解决**：
```bash
export JAVA_HOME="$HOME/.jdks/corretto-21.0.10"
MVN="/c/Users/莫凡毅/.m2/wrapper/dists/apache-maven-3.9.14/ed7edd442f634ac1c1ef5ba2b61b6d690b5221091f1a8e1123f5fadcc967520d/bin/mvn"
"$MVN" -f <项目pom> compile
```

### 2. PowerShell 里 curl 报错 "无法将 -H 识别为..."

**现象**：PowerShell 中 `curl -H "Content-Type: ..."` 报错。

**原因**：PowerShell 把 `curl` 解析成内置别名 `Invoke-WebRequest`，不是真正的 curl。

**解决**：用 `curl.exe` 代替 `curl`；或切到 Git Bash 终端（项目目录右键 → Git Bash Here）。

### 3. Git Bash 发中文 JSON 导致 50000 系统错误

**现象**：`curl -d '{"appName":"我的登录页",...}'` 返回 `{"code":50000,"message":"系统错误"}`。

**原因**：Windows 终端默认代码页 GBK，curl 命令字符串里的中文被按 GBK 编码发送，而 Spring 服务端按 UTF-8 解析，Jackson 抛 `HttpMessageNotReadableException: Invalid UTF-8 middle byte 0xd2`，被全局异常处理器兜底成 50000。**不是业务 bug**。

**解决**：把中文 JSON 写成 UTF-8 文件，用 `-d @file` 发送（`<<'EOF'` 单引号定界符写文件时不经过终端编码转换）：
```bash
cat > /tmp/body.json <<'EOF'
{"appName":"我的登录页","initPrompt":"生成一个登录页面","codeGenType":"html"}
EOF
curl -X POST "http://localhost:8124/api/app/add" -H "Content-Type: application/json" -b cookies.txt -d @/tmp/body.json
```

### 4. curl 提示"未登录"（cookies.txt 不存在）

**现象**：`-b cookies.txt` 访问需登录接口返回 40100。

**原因**：登录命令没执行成功或 cookie 文件未生成（之前命令在别的目录跑）。

**解决**：先确认登录命令成功且 `-c cookies.txt` 落在当前工作目录；用 `curl -b cookies.txt "http://localhost:8124/api/user/get/login"` 验证登录态。

### 48. 后台 `mvn spring-boot:run` 进程可能意外退出（BUILD FAILURE）

**现象**：用 `mvn spring-boot:run` 后台跑后端，一段时间后任务以 `BUILD FAILURE ... Process terminated with exit code: 1` 退出，后端 8124 端口无监听；此时 `npm run openapi2ts` 报 `ECONNREFUSED`、浏览器接口全挂。

**原因**：`mvn spring-boot:run` 把应用跑在 Maven 进程内，**任何原因导致进程终止（终端关闭、devtools 重启冲突、OOM、Ctrl-C 等）都会连带结束**；日志末尾只看到 Maven 报退出码，真正的终止原因要看更早的输出。

**解决/注意**：
- 检测：`netstat -ano | grep 8124` 或 `curl` 探活；后台任务日志看是否 `BUILD FAILURE`
- 恢复：重新 `mvn spring-boot:run`（或改用 IDE 启动，更可控）
- 依赖后端接口的工具（openapi2ts、浏览器验证）都要先确认后端在跑
**经验**：`mvn spring-boot:run` 适合临时跑、不适合当常驻服务；关键开发会话里定期探活，后端没了先重启再继续（openapi2ts 连不上通常就是后端没起，别先怀疑配置）。

---

## 二、AI 集成

### 5. 测试时看不到 AI 返回内容

**现象**：单元测试里没有真实的 AI 输出。

**原因**：单元测试 mock 了 `ChatModel`，返回的是桩数据，不会真实调用 DeepSeek。这是单元测试的标准做法（不依赖外部服务、不花钱、快）。

**解决**：验证真实输出用 curl 调接口，或写集成测试。

### 6. Mockito 动态加载 agent 警告

**现象**：测试运行时出现 `Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK`。

**原因**：Mockito inline mock 需要运行时动态加载 byte-buddy-agent，JDK 21 开始对动态加载 agent 打警告，未来版本默认禁用。

**影响**：当前不影响运行，只是警告。

**解决**：暂不处理；将来升级 JDK 后若失效，在 pom 的 surefire 配置里用 `-javaagent` 显式挂载 mockito-core + byte-buddy-agent。

### 7. 结构化输出：模型返回 JSON 而非纯文本

**需求**：让 AI 按自定义 POJO（`HtmlCodeResult` / `MultiFileCodeResult`）输出。

**方案**：用 Spring AI 1.1.8 的 `BeanOutputConverter<T>`：
- `converter.getFormat()` 生成 JSON Schema 指令拼进 prompt
- `chatClient.prompt().user(prompt).call().entity(converter)` 直接把模型返回 JSON 反序列化成 POJO

**注意**：DeepSeek **不支持** Native Structured Output（`AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT` 仅 OpenAI GPT-4o / Claude 3.5+ / Gemini / Mistral），只能走 prompt 拼接格式指令的标准路径。

### 8. 流式输出：Flux token 流 + SSE

**需求**：流式接口，前端打字机效果。

**方案**：
- 服务层 `chatClient.prompt().user(...).stream().content()` 返回 `Flux<String>`
- 1.1.8 的 `StreamResponseSpec` 没有流式 `entity()`，需攒满文本后调 `BeanOutputConverter.convert()` 转结构化
- Controller 用 `SseEmitter` 桥接，token 推 `event: token`，结束推 `event: done`（含 VO）
- 回调封装成 `StreamCallback`（onToken / onDone / onError）替代多参数

**坑**：Reactor 的 `subscribe` 错误回调里直接 `throw` 会被吞掉，必须通过 `onError` 回调传给 SSE emitter。

---

## 三、Spring 配置与依赖注入

### 9. 静态工具类 → Spring 组件（放弃硬编码）

**需求**：文件写入工具类从静态方法改为 Spring 注入方式，路径从配置文件读取。

**方案**：`@Service` + `@ConfigurationProperties(prefix="codegen.file")` + `@Data`，字段 `tmpRoot` 自动绑定 `codegen.file.tmp-root`。

### 10. "There is more than one bean of 'String' type"

**现象**：IDEA 报 `Could not autowire. There is more than one bean of 'String' type. Beans: multiFilePrompt, singleHtmlPrompt`。

**原因**：构造器 `CodeFileWriteUtils(String tmpRoot)` 要注入一个 String Bean，但 `AiServiceFactory` 定义了 `singleHtmlPrompt` / `multiFilePrompt` 两个 String Bean，Spring 无法确定注入哪个（运行时会抛 NoUniqueBeanDefinitionException）。

**解决**：改用 `@ConfigurationProperties` + `@Data` **字段绑定**（setter 注入），不经过 Bean 注入，与 `CosClientConfig` 风格一致：
```java
@Service
@ConfigurationProperties(prefix = "codegen.file")
@Data
public class CodeFileWriteService {
    private String tmpRoot;  // 自动绑定 codegen.file.tmp-root
    private String deployRoot;
    private String domain;
}
```

### 11. 每个方法入参必须校验

**规范**：所有方法（Controller / Service / 工具类）入参都要用 `ThrowUtils.throwIf` 校验。

**典型写法**：
```java
ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
ThrowUtils.throwIf(StrUtil.isBlank(x), ErrorCode.PARAMS_ERROR, "xx 不能为空");
ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
```

---

## 四、文件与部署

### 12. 项目文件夹命名：业务类型 + 雪花 ID → 业务类型 + appId

**需求**：保存文件命名改为 `codeGenType_appId`。

**方案**：`createProjectDir` / `saveHtmlCode` / `saveMultiFileCode` 增加 `Long appId` 参数，删除 `IdUtil` 雪花 ID 依赖，一路从 Controller → Facade → CodeFileWriteService 透传。

### 13. 部署目录嵌套错误（nginx 403）

**现象**：部署后 nginx 访问 `http://localhost/{deployKey}` 返回 **403 Forbidden**，目录结构变成 `deploy/{deployKey}/{codeGenType}_{appId}/index.html`。

**原因**：Hutool `FileUtil.copy(src, dest, true)` 在 dest **不存在**时，会把 src 作为 dest 的**子目录**复制（而非复制内容），导致嵌套一层目录，nginx 在 `deploy/{deployKey}/` 下找不到 `index.html`。

**解决**：先 `FileUtil.mkdir(target)` 建好目标目录，再 `FileUtil.copyContent(source, target, true)` 复制**内容**：
```java
if (!FileUtil.exist(target.toFile())) {
    FileUtil.mkdir(target.toFile());
    FileUtil.copyContent(source.toFile(), target.toFile(), true);
}
```

### 14. nginx 配置

**方案**：追加一个 server 块服务部署目录，不改动原有 server：
```nginx
server {
    listen 80;
    server_name localhost;
    root D:/IDEAproject/xiaoyu_zerocode/deploy;
    index index.html;
    location / { try_files $uri $uri/ =404; }
}
```
部署接口返回 `domain + "/" + deployKey`，浏览器访问 `http://localhost/{deployKey}`。

**联动**：改 nginx `server_name` 时要同步改 `application.yml` 的 `codegen.file.domain`。

---

## 五、MyBatis-Flex 与数据库

### 15. 逻辑删除的记录查询不到（回收站/恢复）

**现象**：应用逻辑删除后（`isDelete=1`），`getById` / `selectOneByQuery` 查不到——MyBatis-Flex 默认自动加 `WHERE isDelete=0` 过滤。

**解决**：用 `LogicDeleteManager.execWithoutLogicDelete(() -> ...)` 包一层，临时绕过逻辑删除过滤：
```java
App app = LogicDeleteManager.execWithoutLogicDelete(() -> this.mapper.selectOneByQuery(queryWrapper));
```
恢复 = 忽略过滤查出记录（带 `userId` 条件防越权）→ `updateById` 把 `isDelete` 置回 0。

### 16. 物理删除

**方案**：忽略逻辑删除过滤查出记录（含已删、本人的）→ `CodeFileWriteService.deleteProjectFiles` 清理 tmp/deploy 文件 → `this.mapper.deleteByQuery(QueryWrapper.create().eq("id", id))` 物理删记录。

### 17. `selectOneByQuery` 编译找不到

**现象**：编译报错 `找不到符号 selectOneByQuery(QueryWrapper)`。

**原因**：`selectOneByQuery` 是 **`BaseMapper`** 的方法，不在 `ServiceImpl`/`IService` 上（UserServiceImpl 也是用 `this.mapper.selectOneByQuery`）。

**解决**：改成 `this.mapper.selectOneByQuery(queryWrapper)`。

### 18. 批量删除/恢复/物理删除

**底层能力**（无需新依赖）：
- `IService.removeByIds(Collection)` — 批量逻辑删除
- `BaseMapper.deleteBatchByIds(Collection)` / `deleteByQuery(QueryWrapper)` — 批量物理删除
- `LogicDeleteManager.execWithoutLogicDelete(Supplier)` — 忽略逻辑删除过滤

**实现**：新增批量接口（`deleteBatch` / `recoverBatch` / `physicalDeleteBatch`），遍历调用对应**单条**方法（复用归属校验，任意一条失败即抛异常中断）。刻意不用 `removeByIds` 批量 SQL，因为每条都要做"只能删自己的"归属校验，物理删除更要逐条清文件。

### 36. QueryWrapper 的 `and(Consumer)` lambda 重载歧义

**现象**：编译报错 `无法将类 QueryWrapper 中的方法 and(LambdaGetter) 应用到给定类型` / `or 应用到给定类型`，找不到 `like(String,String)`，定位在多维搜索的 OR 条件：
```java
queryWrapper.and(w -> w.like("appName", searchText).or().like("description", searchText));
```

**原因**：MyBatis-Flex 1.11.1 的 `QueryWrapper.and` 有**多个重载**：`and(LambdaGetter<T>)`、`and(Consumer<QueryWrapper>)`、`and(QueryCondition)` 等。lambda `w -> ...` 让编译器无法判定选哪个重载（LambdaGetter 也是单参数函数式接口），且 `or()` 无参方法在 `QueryWrapper` 上不存在（推断走错分支导致连 `like` 都找不到）。显式强转 `(Consumer<QueryWrapper>) w -> ...` 或 `and((QueryWrapper w) -> ...)` 也无法消除歧义。

**解决**：改用 `QueryCondition` + `QueryColumn` 显式组合 OR 条件，绕开 lambda 重载：
```java
if (StrUtil.isNotBlank(searchText)) {
    QueryCondition nameCond = QueryCondition.create(new QueryColumn("appName"), "like", "%" + searchText + "%");
    QueryCondition descCond = QueryCondition.create(new QueryColumn("description"), "like", "%" + searchText + "%");
    QueryCondition tagsCond = QueryCondition.create(new QueryColumn("tags"), "like", "%" + searchText + "%");
    queryWrapper.and(nameCond.or(descCond).or(tagsCond));
}
```
`QueryCondition.or(QueryCondition)` 是明确的链式 API，无重载歧义。**经验**：MyBatis-Flex 条件构造优先用 `QueryCondition`/`QueryColumn` 显式对象，lambda 写法在有重载时会踩类型推断坑。

### 37. QueryCondition 传字符串操作符拼出非法 SQL（"lt"/"eq" → 50000）

**现象**：对话历史游标分页 `/chatHistory/list/page/vo` 带 `beforeCreateTime` 时返回 `{"code":50000,"message":"系统错误"}`；不带游标参数时正常。

**原因**：`QueryCondition.create(new QueryColumn("createTime"), "lt", value)` 的字符串操作符 `"lt"` 被 MyBatis-Flex 1.11.1 **原样拼接到 SQL**（不映射成 `<`），生成 `` `createTime`lt'2026-08-10 09:43:49' `` 非法 SQL，执行抛异常。反编译 jar 的字节码确认：`create(QueryColumn, String, Object)` 只是把 String 存进 `logic` 字段，SQL 拼装时直接输出该字符串。**`"like"` 恰好是合法 SQL 关键字，所以 `AppServiceImpl` 的 searchText 用同样写法碰巧能用，掩盖了这个坑。**

**排查要点**：
1. 区分"反序列化失败"和"SQL 错误"：`beforeCreateTime=12345`（非法类型）→ 50000（反序列化失败）；`appId=0` + 合法时间 → 40000（反序列化成功、业务校验失败）。反序列化成功仍 50000 → 问题在 SQL 层。
2. 反编译 jar + 写最小 Java probe 调 `queryWrapper.toSQL()` 打印实际 SQL，一眼看出 `` `createTime`lt'...' `` 少了空格/操作符。

**解决**：改用 `SqlOperator` 枚举：
```java
QueryCondition timeLessCond = QueryCondition.create(new QueryColumn("createTime"), SqlOperator.LT, beforeCreateTime);
QueryCondition timeEqCond  = QueryCondition.create(new QueryColumn("createTime"), SqlOperator.EQUALS, beforeCreateTime);
QueryCondition idLessCond  = QueryCondition.create(new QueryColumn("id"), SqlOperator.LT, beforeId);
```
**经验**：MyBatis-Flex 条件操作符一律用 `SqlOperator` 枚举，不要传字符串——只有恰好等于 SQL 关键字的字符串（`like`）才碰巧能用，`lt`/`eq`/`gt` 这类一律踩坑。

### 44. 分组分页聚合查询：`pageAs` + `groupBy` 返回自定义 VO

**需求**：对话管理页要"一个应用一行"，展示每个应用的消息数、最近对话时间。即 `chat_history` 按 `appId` 分组聚合、分页、返回自定义 VO（`ChatAppVO`）。

**方案**（[ChatHistoryServiceImpl.java](src/main/java/com/mofanyi/service/impl/ChatHistoryServiceImpl.java)）：
```java
QueryWrapper qw = QueryWrapper.create()
        .select("appId", "count(*) as messageCount", "max(createTime) as lastCreateTime")
        .from(ChatHistory.class)
        .groupBy("appId")
        .orderBy("lastCreateTime", false);
Page<ChatAppVO> page = this.pageAs(Page.of(pageNum, pageSize), qw, ChatAppVO.class);
```
关键点：
- `pageAs(Page, QueryWrapper, VO.class)` 分组分页由框架自动处理（count 用子查询），无需手写 count
- **字段别名（`as messageCount`）必须与 VO 驼峰字段名一致**，`pageAs` 才能映射成功（`messageCount` ↔ `messageCount`）

**补关联字段防 N+1**：聚合结果里没有 `appName`（在 app 表），逐条查会 N+1。改为一次 `IN` 批量查再组装：
```java
Map<Long, App> appMap = appMapper.selectListByIds(appIds).stream()
        .collect(Collectors.toMap(App::getId, a -> a, (a, b) -> a));
for (ChatAppVO vo : list) { App app = appMap.get(vo.getAppId()); if (app != null) { vo.setAppName(app.getAppName()); ... } }
```
**经验**：MyBatis-Flex 聚合 + 分页 + 自定义 VO 一条 `pageAs` 搞定；关联表补字段一律批量 `IN` 查询再 map，别写循环单查。

---

## 六、测试

### 19. 单元测试 mock ChatModel 返回结构化结果

**要点**：mock `ChatModel` 的 `call(any(Prompt.class))` 返回 `ChatResponse`（用 `AssistantMessage` 构造 Generation，注意 1.1.8 的 `Generation` 只能由 `AssistantMessage` 构造），再用 `ArgumentCaptor<Prompt>` 断言拼好的 prompt 是否包含 schema 指令与用户消息。mock 返回的 JSON 文本会被 `BeanOutputConverter` 真实反序列化，能验证 JSON→POJO 转换。

### 20. 修改构造器后测试要同步更新

**现象**：`AiCodeGeneratorServiceImpl` 构造器加了 `CodeParser` 参数后，测试编译失败。

**解决**：测试里 `new AiCodeGeneratorServiceImpl(chatModel, ..., new CodeParser())` 传真实 `CodeParser`（无副作用）。

---

## 七、前端（Vue 3 + Ant Design Vue 4）

### 21. 雪花 ID 精度丢失（最重要）

**现象**：进入应用对话页/编辑页后，接口返回"请求数据不存在"（40400），或操作报错；雪花 ID 末尾几位变成 0。

**原因**：后端用雪花 ID 作主键（如 `443253985292632064`），超 JS `Number.MAX_SAFE_INTEGER`（≈9×10¹⁵）。前端 `Number(route.params.id)` 转换会丢精度（`...2064` → `...2060`），后端查不到。

**解决**：**前端全程按字符串传递 ID**，禁止 `Number()` 转换：
```ts
// 路由参数：直接作为字符串，不转 number
const appId = computed(() => route.params.id as string)
// 调用生成的接口：类型断言（id 实际是字符串）
getAppVoById({ id: appId.value as unknown as number })
```
后端 `JsonConfig` 已把 Long 序列化为字符串，且 Jackson 反序列化接受字符串 JSON body（`{"id":"443253985292632064"}`），所以字符串传参两端都兼容。

### 22. 流式打字机效果失效（一次性蹦出）

**现象**：AI 流式回复不是逐字显示，而是等完成后一次性整段出现。

**原因**：`aiMsg` 用普通对象字面量创建后 push 进 `messages`（ref 数组）。Vue 把数组元素 reactive 化（存 proxy），但闭包里 `aiMsg` 引用的是**原始对象**——`aiMsg.content = ...` 通过原始对象赋值**不触发 proxy 的 set trap**，UI 不刷新，直到其他 ref 更新触发整树重渲染才一次性显示。

**解决**：用 `reactive()` 创建：
```ts
const aiMsg = reactive<ChatMessage>({ role: 'ai', content: '', status: 'streaming' })
messages.value.push(aiMsg)
```

### 23. 流式对话框不显示代码（只显示 description）

**现象**：streaming 期间 AI 消息只有 description 文字，代码块不出现；完成后才整段出现。

**原因**：reveal 定时器每 20ms 把 `aiMsg.content` 向前推进 1 字符，但 htmlCode 在模型输出里**极快到达**（几秒内几千字符），reveal 逐字推进根本追不上；且 description 打字机与 onToken 整段更新互相覆盖。

**解决**：**代码块出现前用打字机，代码出现后停掉 reveal、改由 onToken 整段刷新**：
```ts
if (md.includes('```')) {   // 代码块已到达
  if (!codeStarted) { codeStarted = true; stopReveal() }
  aiMsg.content = md        // 整段刷新，代码实时可见
} else {
  targetText = md           // description 阶段仍打字机
}
```
同时用 `extractFieldLinear` 线性扫描流式 JSON 提取 htmlCode/cssCode/jsCode（字段未闭合时返回已到达部分并手动反转义）。

### 24. Vue 模板中 ref 的 `.value` 报错（TS2551）

**现象**：模板里写 `onChange: (keys) => (myAppSelectedRowKeys.value = keys)` 报 `Property 'value' does not exist on type 'number[]'`。

**原因**：Vue 3 模板对 `setup` 中返回的 ref **自动解包**，模板表达式里 `myAppSelectedRowKeys` 就是 `number[]`，没有 `.value`。模板编译器对解包 ref 的**赋值会自动写回 `.value`**。

**解决**：模板里直接 `myAppSelectedRowKeys = keys`（编译器自动写回），**不要写 `.value`**。

### 25. openapi2ts 误删手写文件

**现象**：`npm run openapi2ts` 后报 `Cannot find module '@/api/aiStream'`。

**原因**：openapi2ts 生成时会**清空 `src/api/` 下非它生成的文件**，`aiStream.ts`（手动写的 SSE 封装）被删。

**解决**：`aiStream.ts` 放在 `src/api/` 会被清，**改放 `src/utils/` 或重新生成后重建**；或生成前备份。`sse.ts` 在 `src/utils/` 不受影响。

### 26. SFC 中 `</script>` 提前截断脚本块

**现象**：`AppGeneratePage.vue` 编译报 `Unterminated template literal` 或语法错误。

**原因**：SFC 解析器扫描 `<script>` 内容时遇到**字面量 `</script>`**（即使在模板字符串或注释里）就认为脚本块结束，导致后续代码被截断。本项目多文件预览拼接 HTML 时含 `</script>`。

**解决**：用字符串拼接拆开字面量，避免直接出现 `</script>`：
```ts
'<' + 'script>' + (result.jsCode ?? '') + '</' + 'script>'
```
注意：**注释里也不能写** `</script>` 字样。

### 27. 生成失败：JsonMappingException（后端）

**现象**：前端显示 `生成失败：com.fasterxml.jackson.databind.JsonMappingException: Unexpected end-of-input... expecting closing quote for a string value`，定位在 `htmlCode` 字段。

**原因**：deepseek-chat 默认 `max_tokens`（约 4K）对长 HTML 不够，模型输出偶发在 htmlCode 处**截断**，JSON 不完整，`BeanOutputConverter` 解析失败。

**解决**（后端）：
1. `application.yml` 加 `max-tokens: 8192`（输出上限翻倍，减少截断概率）
2. `CodeParser.parseHtmlCode/parseMultiFileCode` 改为**容错解析**：正常转换失败时，用线性扫描提取 description/htmlCode/cssCode/jsCode，被截断的字符串返回已到达部分，不再抛异常

### 28. 已生成应用每次进入都重新生成（卡顿）

**现象**：从精选/我的应用点进对话页，每次都重新调用 AI 生成，卡几十秒。

**原因**：`loadApp` 只要拿到 initPrompt 就自动 `sendMessage` 触发流式生成。

**解决**：后端新增 `GET /app/get/code?id=` 接口（`CodeFileWriteService.readProjectCode` 读 tmp 目录已生成代码），前端 `tryLoadExistingCode` 优先读已有代码直接展示；没有才自动生成。

### 29. 精选应用列表未过滤部署状态

**现象**：精选应用包含未部署的应用。

**解决**：后端 `listFeaturedAppVOByPage` 加 `isNotNull("deployKey")` 过滤——精选仅展示已部署上线的应用。

### 30. Markdown 渲染 + 代码高亮

**方案**：`markdown-it` + `highlight.js` 渲染 AI 回复：
```bash
npm i markdown-it highlight.js
npm i -D @types/markdown-it
```
- `MarkdownView.vue` 组件：`new MarkdownIt({ html:false, linkify:true, breaks:true, highlight })`，highlight 用 `hljs.highlight(code, { language, ignoreIllegals:true }).value`
- `import 'highlight.js/lib/common'` 只注册常用语言，避免全量体积过大；主题 `highlight.js/styles/github.css`
- AI 生成结果用 `buildMarkdownContent(result)` 拼成 `description + #### index.html + \`\`\`html 代码块`（多文件含 css/js）

### 31. 部署 URL 打不开（nginx 未运行）

**现象**：部署后访问 `http://localhost/pnwxup` 连接失败/超时。

**原因**：nginx 没启动（80 端口无监听），部署产物其实已正确落盘。

**解决**：启动 nginx（`cd nginx目录 && ./nginx.exe`），部署目录由 nginx server 块 `root D:/IDEAproject/xiaoyu_zerocode/deploy` 托管。注意 nginx **不会开机自启**。

### 32. 后端 OpenAPI 文档接口 500（knife4j）

**现象**：`/v3/api-docs` 返回 500，`NoSuchMethodError: 'java.util.List ... getGroupConfigs()'`。

**原因**：knife4j 4.5.0 的 `Knife4jOpenApiCustomizer` 按 `List<GroupConfig> getGroupConfigs()` 编译，springdoc 2.8.x 改成返回 `Set`，运行时签名不匹配。**knife4j 4.5.0 只兼容 springdoc 2.3.0**（字节码实测验证，网上的"2.8.9 兼容"说法是错的）。

**解决**：移除 knife4j 依赖，保留 springdoc 2.8.13（原生 swagger-ui 够用），删掉 application.yml 的 knife4j 配置。

### 33. AppVO 缺 deployKey 字段，无法判断部署状态

**现象**：对话页要按"是否已部署"切换部署/下线按钮，但 `AppVO` 类型没有 `deployKey` 字段，`getAppVoById` 返回的数据里也没有，TS 编译报 `Property 'deployKey' does not exist`。

**原因**：后端 `AppVO`（视图对象）设计时只拷贝展示字段，没包含 `deployKey`（部署标识）。而部署状态（`deployKey` 非空 = 已部署）是前端判断"显示部署还是下线"的唯一依据。

**解决**：
1. 后端 [AppVO.java](src/main/java/com/mofanyi/model/vo/AppVO.java) 加 `deployKey` 字段
2. `getAppVO` 用 `BeanUtil.copyProperties(app, appVO)` 自动拷贝，无需改组装逻辑
3. 重跑 `npm run openapi2ts` 让前端类型同步
4. 前端 `app.deployKey ? '下线' : '部署'` 切换部署/下线

**注意**：`App`（管理页用）原本就有 deployKey，只有 `AppVO`（用户侧列表/详情用）缺。

### 34. DeepSeek 长 HTML 被截断（max_tokens 8192 需 beta 接口）

**现象**：生成的复杂页面（电商详情页等）在 `htmlCode` 处被截断，JSON 解析失败，前端报"生成失败：JsonMappingException"。加大 max-tokens 到 8192 后仍偶发截断。

**原因**：**DeepSeek 官方文档说明 `max_tokens` 上限 8192 必须走 beta 接口**（`https://api.deepseek.com/beta`）才生效；普通接口只认 4096。之前 base-url 是 `https://api.deepseek.com`（非 beta），所以实际还在 4096 上限附近，复杂页面必超。

**解决**：`application.yml` 的 `base-url` 改为 `https://api.deepseek.com/beta`，配合 `max-tokens: 8192`，让 8K 输出真正生效，极大减少截断。

### 35. AI 流式网络断连（Connection reset）

**现象**：后端日志出现 `MessageAggregator: Aggregation Error` + `WebClientRequestException: Connection reset`，请求目标是 `https://api.deepseek.com/beta/v1/chat/completions`。前端可能显示"生成失败"。

**原因**：DeepSeek 服务端/网络**偶发断连**（搜索确认是已知问题：网关 600 秒连接上限、高峰期主动断开），非代码 bug。3 次连续流式测试均正常，确认为偶发。

**解决**（两层防护）：
1. **后端** [AiCodeGeneratorFacade.java](src/main/java/com/mofanyi/core/AiCodeGeneratorFacade.java)：两个流式方法的 error 分支，**先尝试用已累积的 token 容错解析并落盘**（保住已生成的部分代码），解析不出才标记失败。新增 `tryRecoverHtml` / `tryRecoverMultiFile` 方法。
2. **前端** [AppGeneratePage.vue](xiaoyuZeroCode-front/src/views/app/AppGeneratePage.vue)：新增 `friendlyError` 函数，把 `connection reset` 等网络错误映射为"**生成失败：网络连接不稳定，请重试**"，不再把技术堆栈抛给用户。

### 45. 预览工具（preview_*）与已运行的 dev server 冲突（孤儿 vite 进程占端口）

**现象**：`preview_start` 报 `Port 5173 is in use by "node.exe" (not a preview server)`，无法启动受管理的 dev server。

**原因**：之前用别的方式（Bash 直接起 vite / 残留进程）启动的前端 dev server 仍在运行，但**预览管理器不跟踪它**——preview_* 工具只能接管自己通过 `preview_start` 起的 server。

**解决**：定位并杀掉占用端口的孤儿进程，再 `preview_start`：
```bash
# 定位占用进程
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object {$_.CommandLine -match 'vite'} | Select ProcessId, CommandLine | Format-List"
# 杀掉（确认是 vite 后再停）
powershell -NoProfile -Command "Stop-Process -Id <PID> -Force"
```
**经验**：前端 dev server 尽量统一用 `preview_start` 启动，避免脱离预览管理；端口被占时先 `Get-CimInstance` 确认占用进程是 vite 再停，别误杀其他服务。

### 47. 浏览器验证 admin 权限页：HttpOnly cookie 无法切会话，临时改库角色

**现象**：浏览器登录的是普通用户（`dev_test_2026`），访问 `/admin/chat` 被路由守卫拦到"无权限访问"，无法用浏览器验证 admin 页。

**原因**：登录态 session 是 **HttpOnly cookie**，`document.cookie = 'SESSION=xxx'` 无法覆盖（HttpOnly 禁止 JS 读写）；又没有 admin 账号密码可登录。

**解决**：临时把测试账号提升为 admin，验证完立刻恢复：
```bash
# 验证前提权
node -e "const m=require('<mysql2>');(async()=>{const c=await m.createConnection({host:'localhost',user:'root',password:'...',database:'xiaoyu_zerocode'});await c.query(\"UPDATE user SET userRole='admin' WHERE userAccount='dev_test_2026'\");await c.end()})()"
# 重新登录/刷新让后端返回新角色（角色在登录时写入 session）
# 验证完恢复
# UPDATE user SET userRole='user' WHERE userAccount='dev_test_2026'
```
**注意**：改角色后**必须重新登录**（或刷新页面触发重新 `fetchLoginUser`），否则 session 里缓存的还是旧角色。
**经验**：浏览器无法绕过 HttpOnly cookie 换会话；临时改库角色 + 验证后恢复是可靠的浏览器验证手段，但记得收尾恢复，别把测试账号留在 admin 角色。

---

## 八、对话记忆与历史（Redis ChatMemory + MySQL 双写）

### 38. 后端生成流程不落库 chat_history → 部署后回来重新生成

**现象**：创建应用并部署后，重新进入该应用对话页，每次都重新调用 AI 生成，卡几十秒；已生成的代码没被展示。

**原因**：`ChatHistoryService.saveMessage(...)` 接口和实现都已写好，但 AI 生成链路（`AiChatController` → `AiCodeGeneratorFacade`）**从未调用它**，`chat_history` 表从无数据。前端逻辑是"自己的 app 且无对话历史 → 自动发 initPrompt"，历史永远为空 → 每次进页面都重新生成；且历史 < 2 条不触发网站展示分支，`getAppCode` 读到的已有代码也被跳过。

**解决**：AI 生成链路双写落库（详见本章）：
1. 生成前 `chatHistoryService.saveMessage(appId, userId, message, "user", null)` 拿 userMsgId
2. 完成后 `saveMessage(appId, userId, aiContent, "assistant", userMsgId)`（aiContent 拼成 description + 代码块 Markdown，与前端展示格式一致）
3. 失败 `saveMessage(..., "error", userMsgId)`（截断 500 字符防堆栈超长）
前端自动判断（有历史不重发）随即生效。

### 39. Spring AI 官方没有 Redis ChatMemoryRepository

**需求**：用 Redis 做 AI 对话记忆，但官方文档（chat-memory.adoc）列出的 `ChatMemoryRepository` 实现只有 InMemory/Jdbc/Cassandra/Neo4j/CosmosDB/Mongo，**没有 Redis**。

**解决**：自研实现，接口仅 4 个方法，用 `StringRedisTemplate` + JSON（[RedisChatMemoryRepository.java](src/main/java/com/mofanyi/config/RedisChatMemoryRepository.java)）：
- key = `spring:ai:chat:memory:{conversationId}`（本项目 conversationId = appId）
- 值 = JSON 数组 `[{"type":"user","text":"..."},{"type":"assistant","text":"..."}]`
- `findConversationIds()`：SCAN 前缀 `spring:ai:chat:memory:*`，**勿用 KEYS**（大数据量阻塞 Redis）
- `findByConversationId()`：GET + 反序列化 → 重建 `Message`
- `saveAll()`：全量覆盖 SET（`MessageWindowChatMemory` 是整读整写，每次保存裁剪后的完整窗口）
- `deleteByConversationId()`：DEL

**注意**：`Message` 是抽象多态类型，直接 Jackson 序列化麻烦。用自定义 record `{type,text}`（type 存 `MessageType.name().toLowerCase()`），反序列化时按 type 重建 `UserMessage`/`AssistantMessage`/`SystemMessage`。

配置（[ChatMemoryConfig.java](src/main/java/com/mofanyi/config/ChatMemoryConfig.java)）：
```java
@Bean
public ChatMemory chatMemory(RedisChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(20)   // 最近 20 条消息窗口
            .build();
}
```

### 40. MessageChatMemoryAdvisor 会把完整 system prompt 存进记忆

**现象**：挂上 `MessageChatMemoryAdvisor` 后，Redis 里存的记忆第一条是**完整 system prompt 文本**（作为 user 消息），而非真实对话。窗口被大量重复的 system prompt 占用，后续对话上下文被污染；且 AI 回复不会自动进记忆。

**原因**：advisor 默认把发给模型的**整个 user prompt**（system prompt + 当前消息拼接后的长文本）存入记忆。

**解决**：**不用 advisor**，在 `AiCodeGeneratorServiceImpl` 手动读写记忆：
```java
// 调用前：读历史注入 prompt（作为前置 messages）
List<Message> history = chatMemory.get(String.valueOf(appId));
chatClient.prompt().system(systemPrompt).messages(history).user(fullPrompt).call()...
// 调用后：写入本次对话
chatMemory.add(String.valueOf(appId), List.of(new UserMessage(msg), new AssistantMessage(reply)));
```
这样记忆里只存真实对话，干净且可控。**经验**：Spring AI 的 memory advisor 默认存整个 user prompt，想只存真实对话要手动 `get`/`add`。

### 41. 流式生成时对话记忆的写入时机

**需求**：流式生成时 `Flux<String>` 在 Reactor 内异步执行，无法在返回前同步写入记忆；且若在 Flux 构造前先写 user 消息、再读 `history`，会把自己重复注入 prompt。

**解决**：先读历史（不含本次）→ 再写本次 user 消息 → Flux 里攒 token 文本，`doOnComplete` 写 assistant 消息：
```java
List<Message> hist = history(appId);      // 1. 先读，不含本次
addUserMessage(appId, userMessage);       // 2. 再写本次 user
return chatClient.prompt().system(...).messages(hist).user(...).stream().content()
    .doOnNext(token -> raw.append(token))                 // 3. 攒流式文本
    .doOnComplete(() -> addAssistantMessage(appId, raw.toString()));  // 4. 完成写 assistant
```
非流式则调用完成后一次性 `add(user + assistant)`。记忆写入失败不影响生成主流程（try/catch 吞掉）。

### 42. Redis `Cursor` try-with-resources 编译报 unreported IOException

**现象**：`try (Cursor<byte[]> cursor = connection.scan(...)) { ... } catch (IOException e)` 编译报 `未报告的异常IOException; 必须对其进行捕获或声明以便抛出`。

**原因**：Spring Data 的 `Cursor` 同时继承 `AutoCloseable.close() throws Exception` 和 `Closeable.close() throws IOException`，javac 分析资源关闭路径时按**最宽的 `Exception`** 处理；且 `scan()` 初始化表达式抛的检查异常不会被**同一 try** 的 catch 捕获。用最小 Java 测试确认：`catch (Exception)` 能过，`catch (IOException)` 报错。

**解决**：外层再包一层 try 捕获 `Exception`：
```java
try {
    try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
        while (cursor.hasNext()) { ... }
    }
} catch (Exception e) {
    throw new IllegalStateException("扫描对话记忆失败", e);
}
```

### 43. 老版本 Redis 连不上（node redis 客户端报 HELLO 3）

**现象**：验证 Redis 记忆时，node 官方 `redis` 客户端 `connect()` 报 `ERR unknown command 'HELLO', with args beginning with: '3'`；`redis-cli` 本机也没装。

**原因**：本机 Redis 版本较老，不支持 RESP3 的 `HELLO` 握手，node 官方客户端（默认走 RESP3）连不上。

**解决**：用原生 RESP 协议经 net socket 直接发命令验证（也可绕开客户端版本问题）：
```bash
node -e "const n=require('net');const s=n.connect(6379,'127.0.0.1',()=>{s.write('GET spring:ai:chat:memory:123\r\n');s.end()});s.on('data',d=>console.log(d.toString().slice(0,200)));setTimeout(()=>process.exit(0),2000)"
```

### 46. 同 app 并发写 Redis 记忆的读-改-写竞态（隐患）

**现象/分析**：反编译 `MessageWindowChatMemory.add()` 字节码发现它执行的是**非原子的三步**：
```
1. findByConversationId(appId)   ← 读当前窗口
2. process(历史, 新消息)          ← 拼接 + 裁剪到 maxMessages
3. saveAll(appId, 结果)          ← 全量写回 Redis
```
若**同一个 app** 的两个请求并发 `add`（如多端同时发消息），A、B 都读到同一份旧窗口，B 的后写会**覆盖 A 的写入**，导致丢消息（经典的 read-modify-write 竞态）。

**影响评估**：
- 不同 app 之间安全（key 不同，`spring:ai:chat:memory:{appId}` 天然隔离）
- 当前前端有 `generating` 状态禁止同一页面重复发送，单用户单应用几乎不触发
- chat_history（MySQL）双写不受影响，前端展示靠 MySQL，**用户可见影响很小**——所以这是隐患而非已暴露 bug

**解决方向**（如需加固）：按 appId 分段锁，只串行化同 app 的写，不同 app 仍并行：
```java
private final ConcurrentHashMap<String, Object> appLocks = new ConcurrentHashMap<>();

private void remember(Long appId, String userMessage, String aiContent) {
    String conversationId = String.valueOf(appId);
    synchronized (appLocks.computeIfAbsent(conversationId, k -> new Object())) {
        chatMemory.add(conversationId, List.of(new UserMessage(userMessage), new AssistantMessage(aiContent)));
    }
}
```

---

## 九、Vue3 工程生成模式

### 49. Vue 部署后页面空白（vite 构建资源绝对路径）

**现象**：部署 vue 应用后访问 `http://localhost/{deployKey}/`，页面空白。浏览器 F12 看 `/assets/index-xxx.js` 404。

**原因**：vite 构建产物默认资源是**绝对路径** `/assets/index-xxx.js`（root-relative）。应用部署在 `/{deployKey}/` 子路径，nginx root 指向 `deploy/` 目录，浏览器请求 `/assets/...` 落到 `deploy/assets/`（不存在）→ 404 → 资源加载失败 → 空白。curl 实测 `/assets/...` 404、`/{deployKey}/assets/...` 200，坐实根因。

**解决**：`vite.config.js` 设置 `base: './'`，让构建产物资源变**相对路径** `./assets/index-xxx.js`：
```js
export default defineConfig({ base: './', plugins: [vue()] })
```
部署到任意子路径都能正常加载。已在 `codegen_vue_prompt.txt` 里强制要求生成该配置。

### 50. 部署 done 事件发裸 URL → 前端"解析生成结果失败"

**现象**：vue 部署成功后前端报 `解析生成结果失败: http://localhost/xxx`，不打开新窗口。

**原因**：前端 `sse.ts` 对 SSE 的 `done` 事件**统一 `JSON.parse(event.data)`**（生成接口的 done 发 VO JSON）。但部署接口 `/app/deploy/stream` 的 done 发的是**裸 URL 字符串**，不是 JSON → 解析失败。

**解决**：后端 `/app/deploy/stream` 的 done 改为发 JSON 对象 `{"url":"..."}`，与生成接口 done 格式一致；前端 onDone 从 `result.url` 取：
```java
send(emitter, "done", java.util.Map.of("url", url));
```
**经验**：SSE 的 `done`/`error` 事件 data 格式要在前后端对齐——要么都 JSON，要么前端按接口区分解析。

### 51. 修改对话重新生成后，预览/部署仍是旧页面

**现象**：vue 应用生成后预览正常；要求 AI 修改（如改绿色），代码确实重新生成了，但页面预览/部署**没有变化**，还是旧样式。

**原因**：`getVuePreview` 只在 **dist 不存在**时才构建。修改重新生成只更新了源码文件，dist 仍存在（旧构建），所以预览直接返回旧 dist。

**解决**：**vue 每次生成完成后失效 dist**——源码更新后删除 dist，下次预览/部署自动重建：
- `CodeFileWriteService.deleteVueDist(appId)`：删 `vue_{appId}/dist`
- `AiCodeGeneratorFacade` 两处 vue 生成完成（`generateVueProject` / `streamGenerateVueProject` onDone）后调它
```java
codeFileWriteService.deleteVueDist(appId);
```
**经验**：构建产物（dist）是源码的"缓存"，源码更新必须让缓存失效，否则预览/部署一直读旧产物。

### 52. `Executors.newCachedThreadPool()` 不受 Spring 管理

**现象**：`AiCodeGeneratorFacade` 用 `static final ExecutorService VUE_EXECUTOR = Executors.newCachedThreadPool()` 跑 vue 生成——无界线程池（任务多时线程爆炸）、不受 Spring 管理（无法配置/优雅关闭）。

**解决**：线程池 Spring 化：
1. `ThreadPoolProperties`：`@ConfigurationProperties(prefix="thread-pool.vue")` 绑定 yml（core/max/keepAlive/queue/threadNamePrefix）
2. `ThreadPoolConfig`：`@Bean ThreadPoolExecutor`，直接 `new ThreadPoolExecutor(core, max, keepAlive, TimeUnit.SECONDS, new LinkedBlockingQueue<>(queue), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy())`，自定义 daemon 线程工厂 `vue-gen-N`
3. `AiCodeGeneratorFacade` 构造器注入 `ThreadPoolExecutor` Bean，删静态常量

**经验**：Java 并发工具（线程池）在 Spring 项目里要用 Bean 管理，`Executors.newCachedThreadPool()` 的无界性质 + 非托管是隐患。拒绝策略 `CallerRunsPolicy`（队列满时调用线程执行，不丢任务）。

### 53. vue 对话消息"每生成一个文件整块渲染"，无流式打字机效果

**现象**：vue 模式生成时，AI 消息区每写完一个文件就**整块瞬间出现**该文件代码，用户要的是像 html 模式那样逐段流式显示。

**原因**：vue agent 是**逐文件生成**（SSE `agent_progress` 每次携带一个完整文件内容），前端 `onAgentProgress` 直接 `push` + `join` 整段刷新，无打字机过程感。

**解决**：前端实现**打字机 reveal**——收到文件入队，定时器逐段推进到消息区：
```ts
const revealQueue: { path: string; content: string }[] = []
let revealTimer = setInterval(() => {
  if (currentTarget) {
    currentPos = Math.min(currentTarget.length, currentPos + REVEAL_CHUNK)
    // 显示 currentTarget.slice(0, currentPos)，放完 push 进已显示列表，取队列下一个
  }
}, REVEAL_INTERVAL)
```
- `REVEAL_CHUNK`（每 tick 追加字符）/ `REVEAL_INTERVAL`（tick 间隔）控制速度
- 一个文件放完自动衔接下一个；队列空 + 收到 done → 补 description 前缀、更新文件树、触发构建预览
- `onError`/`onClose` 清定时器

**验证**：浏览器采样消息长度渐进增长（`1242→1388→1532…→8083`，每 150ms +约140 字符），确认逐段 reveal 生效。
**注意**：vue agent 逐文件生成本身很快（单文件<1s），打字机只是"表演式"把已生成内容逐段放出，实现流式观感——无法做到 html 模式那种逐 token 的真实流式（那是 token 流，vue 是文件流）。

- **50000 系统错误** 先看 IDEA 控制台完整堆栈（`GlobalExceptionHandler` 会打 `log.error("RuntimeException", e)`），比猜快得多。
- **接口 50000 先排除中文编码**（Windows curl 常见），用英文 JSON 复测能快速区分"业务 bug"和"编码问题"。
- **改代码后要重启应用** 才生效（`mvn compile` 只验证编译，运行时还是旧 class）。
- **cookie 文件** 用 `-c 保存 -b 发送`，注意文件落在当前工作目录。
- **雪花 ID 类接口返回 40400/查不到** 先怀疑前端 `Number()` 精度丢失，排查是否按字符串传 ID。
- **Vue 里"数据变了但界面不刷新"** 优先怀疑响应式丢失（普通对象/非 reactive 赋值、ref 模板解包误用 `.value`）。
- **流式/打字机"看不到中间过程"** 用密集采样（`setInterval` 每 400ms 记录 DOM 状态）确认是数据没到还是渲染被合并，再对症下药。
- **`Connection reset` / `Connection closed`（AI 流式）** 多为 DeepSeek 服务端/网络偶发断连（搜索确认：网关 600s 上限、高峰期主动断开），非代码 bug。防御：后端 error 分支先用已累积 token 容错解析兜底，前端把网络错误映射为"网络连接不稳定，请重试"友好文案。**注意** beta 接口（`/beta`）才能支持 max_tokens 8192，普通接口只认 4096。
- **MyBatis-Flex 条件操作符**一律用 `SqlOperator` 枚举，字符串 `"lt"`/`"eq"`/`"gt"` 会原样拼 SQL 报 50000；`"like"` 碰巧是 SQL 关键字才没暴露（见 37）。
- **50000 先分「反序列化失败」vs「SQL 错误」**：故意传非法类型（如时间字段传数字）看是否仍 50000；再传会走业务校验的参数（如 `appId=0` → 40000）确认反序列化成功，即可把问题定位到 SQL 层。
- **排查 ORM 生成的 SQL**：javap 反编译 jar + 写最小 Java probe 调 `queryWrapper.toSQL()` 打印实际 SQL，比猜快得多（见 37）。
- **Spring AI 的 memory advisor 默认存整个 user prompt**（含 system prompt），想只存真实对话要手动 `chatMemory.get`/`add`（见 40）。
- **对话记忆双写思路**：Redis 存 AI 上下文窗口（20 条），MySQL `chat_history` 存完整历史（前端展示 + 判断是否重生成），两者独立、互不依赖。改对话功能时先确认这两层各自的写入时机。
- **老版本 Redis（不支持 HELLO 3）** node 官方 redis 客户端连不上，可用原生 RESP 经 net socket 发命令验证 key 值（见 43）。
- **操作 MySQL 验证/造数**：本机没装 mysql 客户端时，可临时 `npm install mysql2` 到临时目录，用 Node 脚本连库查数/造数/清理（项目密码在 `application-location.yml`）。比 curl 调接口直接、能看原始表数据。
- **浏览器验证 admin 页**：HttpOnly cookie 无法 JS 切换会话，临时 `UPDATE user SET userRole='admin'` 提权验证，验证后恢复；改角色后要重新登录才生效（见 47）。
- **preview 工具只能接管自己起的 server**：孤儿 vite 进程占端口时，`Get-CimInstance` 定位 PID 后 `Stop-Process -Force`，再 `preview_start`（见 45）。
- **vite 部署到子路径空白**：构建产物资源绝对路径 `/assets/...` 在子路径 404，`vite.config.js` 设 `base: './'`（见 49）。
- **SSE done 事件格式要对齐前后端**：要么都 JSON，要么前端按接口区分解析；裸字符串被统一 `JSON.parse` 必炸（见 50）。
- **构建产物（dist）是源码缓存**：源码更新必须删 dist 失效，否则预览/部署读旧产物（见 51）。
- **Spring 项目线程池要用 Bean 管理**：`Executors.newCachedThreadPool()` 无界+非托管是隐患，改 `ThreadPoolExecutor` Bean + `@ConfigurationProperties`（见 52）。
- **验证打字机/流式是否真在工作**：用 `setInterval` 高频采样 DOM 内容长度，看是否渐进增长（多个不同非零值且递增）；若直接跳变则是整块渲染，若不变则是数据没到（见 53）。

---

## 十、AI 代码生成通用 Agent 与图片功能

### 54. 前端流式打字机 O(n²) 卡死 → 改整文件显示 + 进度条

**现象**：多文件流式生成时，文件生成完毕、后端状态 done，但前端**卡在"生成中"数分钟**，预览界面不结束。

**原因**：打字机逐字符 reveal 每 tick（10ms）执行 `completedText + currentTarget.slice(0, currentPos)` 重建完整字符串。文件多、内容大时（如 4 文件 26KB），每 tick 拼接累计的 completedText + MarkdownView 全量重渲染 → **O(n²) 性能爆炸**，越来越慢永远显示不完。

**解决**：放弃逐字符打字机，改为**每个文件整块显示**，文件间用 80ms 间隔维持"逐文件流式"观感；同时新增 `genProgress` 进度条（"正在生成 index.html…（第 2 个文件）"）传达生成进度，避免用户误判为卡死。
```ts
// 整文件块一次显示，不再逐字符 slice
const agentTick = () => {
  if (revealQueue.length > 0) {
    const next = revealQueue.shift()!
    completedText = (completedText ? completedText + '\n\n' : '')
      + `#### ${next.path}\n\`\`\`html\n${next.content}\n\`\`\``
    aiMsg.content = completedText
    return
  }
  clearInterval(revealTimer!)
  revealTimer = null
  if (donePayload) finishAgent()
}
```
**注意**：html/multi_file 与 vue 两个分支都要同样处理；`currentTarget/currentPos/REVEAL_CHUNK` 等变量一并清理。

### 55. 前后端 SSE 事件名不匹配 → 流式输出失效

**现象**：html/multi_file 流式生成时，前端聊天框**没有任何流式内容**（不像 vue 模式能逐文件显示）。

**原因**：改造后前端 html/multi 分支只监听 `onAgentProgress`（对应 SSE `agent_progress` 事件），但后端 `/chat/stream`、`/chat/multi/stream` 的 `onToken` 仍发 **`token`** 事件（只有 vue 的 `/chat/vue/stream` 发 `agent_progress`）——事件名不匹配，前端收不到进度。

**解决**：`AiChatController` 里 html/multi 的 SSE 事件名 `token` → `agent_progress`，与 vue 一致：
```java
token -> send(emitter, "agent_progress", token)  // 原来是 "token"
```

### 56. html 单文件生成被 max_tokens 截断 → 拆 css/js 分文件写

**现象**：html 模式生成长页面时，writeFile 工具参数 JSON 被截断，报 `Conversion from JSON to WriteFileRequest failed`（`Unexpected end-of-input`）。

**原因**：html 单文件模式让模型把整个页面（含内联 CSS/JS）写进一个 `index.html`，DeepSeek 单次输出上限（beta 接口 max_tokens 8192）不足，tool call 参数被截断。multi_file/vue 拆文件所以不截断。

**解决**：html 模式 prompt 改为允许拆分 `index.html` + `css/styles.css` + `js/app.js`（内容长时必须拆分，防止截断）；后端 `CodeFileWriteService.readProjectCode` 对 HTML 类型也带出 cssCode/jsCode；前端 `buildPreviewDoc`/`buildFormattedCode` 对 html 有 css/js 时也内联展示（否则外链相对路径在 srcdoc iframe 中失效）。

### 57. 模型不调用 searchImage（配图率低）→ 换 deepseek-v4-flash

**现象**：明确要求"带图片"的页面，模型（deepseek-chat）**始终不调用 searchImage**，页面无图或用 `image: ""`/emoji/CSS 渐变占位。

**原因**：deepseek-chat 对工具调用的积极性弱，即使 prompt 明确"涉及图片必须调用 searchImage"也不执行。属模型行为限制。

**解决**：模型换 **deepseek-v4-flash**（API 提示支持的模型名是 `deepseek-v4-pro`/`deepseek-v4-flash`，不是 `deepseek-4-flash`）。v4-flash function calling 正常且主动：curl 实测中文提示词"请务必调用图片搜索工具"时返回 `tool_calls` 并嵌入真实图片 URL。
```yaml
spring:
  ai:
    openai:
      base-url: https://api.deepseek.com/beta
      chat:
        options:
          model: deepseek-v4-flash
```
**注意**：
- v4-flash 是推理模型（响应含 `reasoning_content`），复杂 prompt 下偶发 `event:error`（某次英文 MUST 指令报错），中文明确指令更稳。
- 提示词关键：**中文 + "请务必调用图片搜索工具"** + 明确图片用途（相册/产品卡片/背景），比英文/模糊措辞有效。
- 换模型零成本：改 `spring.ai.openai` 配置即可（OpenAI 兼容端点），不用改代码。

### 58. Pexels API key 偶发 401 Invalid → 瞬时风控，重试恢复

**现象**：searchImage/补图突然全部拿不到图（`{"status":401,"code":"Unauthorized","message":"Invalid API key"}`），页面无图。

**原因**：Pexels 侧瞬时风控/限流，key 本身未失效（重试后恢复）。曾误判为 key 被禁。

**解决**：先 `curl -H "Authorization: Bearer <key>" https://api.pexels.com/v1/search?query=test` 确认；多次重试后恢复即非 key 问题，继续用。真失效才需到 Pexels 控制台重新生成。

### 59. 生成后强制补图 autoFillImages（模型漏写图时兜底）

**现象**：模型生成页面时没调 searchImage，代码里 `<img src="">`、`image: ""`、占位图（picsum/placeholder）、emoji 字段（`emoji: '☕'`）代替真实图片。

**解决**：`VueProjectAgent.generate` 在 `runToolLoop` 后新增 `autoFillImages(type, appId, userMessage)`：
1. 扫描代码文件（html/css/js/vue/json）
2. 正则替换三类缺图信号：
   - JS 数据数组空 `image/img/src/url/cover` 字段 → 填入 COS 真实图
   - `<img src="">` 或占位服务 src（picsum/placeholder）→ 替换真实图
   - `emoji/icon` 字段 → 替换为内嵌 `<img class="autofill-img">`（自动注入 CSS）
3. 复用 `PexelsClient.searchImages` + `downloadAndUpload`（上传 COS）+ `saveAppImage`（落库）
4. 失败兜底：补图异常仅记日志，不影响生成主流程

**设计边界**：补图只能替换"模型已写出的空/占位图片字段"，对"模型完全不写图片结构"的页面（纯 CSS/渐变布局、`image: makeSvgImage(...)` 函数调用）无法注入——正则不匹配函数调用，跳过。如需覆盖需主动往 hero/卡片注入图片（改动布局，有破坏风险，暂缓）。

### 60. 嵌入图片落库：新建 app_image 关联表（1:N）

**现象**：生成的页面嵌入了多张图片，需要记录"这个应用用了哪些图"（管理、清理 COS、展示用）。

**原因**：一个应用多张图片是 1:N 关系，`app` 表加字段存多张（JSON/逗号分隔）是反模式，不好查询/清理。

**解决**：新建关联表 `app_image`（appId + imageUrl + imageType + sortOrder），searchImage 上传成功和补图时都落库；应用删除时级联清理。生成开始时先 `deleteByAppId` 清旧记录，避免重复插入；sortOrder 基于 DB 计数累加，多次 searchImage 调用不重号。
```sql
create table if not exists app_image (
  id bigint auto_increment primary key,
  appId bigint not null comment '应用id',
  imageUrl varchar(512) not null comment '图片 COS URL',
  imageType varchar(32) null comment '用途: main/embed',
  sortOrder int default 0 null comment '排序',
  createTime datetime default CURRENT_TIMESTAMP null,
  updateTime datetime default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP null,
  isDelete tinyint default 0 not null,
  INDEX idx_appId (appId)
) comment '应用嵌入图片' collate = utf8mb4_unicode_ci;
```

### 61. 前端 `request.ts` BASE_URL 硬编码 → 改 vite proxy

**现象**：`BASE_URL = 'http://localhost:8124/api'` 写死后端地址，部署到服务器无法工作；vite.config proxy target 配错（8080）且未生效。

**解决**：`request.ts` 改 `import.meta.env.VITE_API_BASE_URL || '/api'`，开发走 vite proxy（修正 target 为 8124）；部署用 `.env` 的 `VITE_API_BASE_URL` 覆盖。`utils/sse.ts` 复用 BASE_URL，SSE 也自动走 proxy。前后端同源，CORS 不再依赖。

### 62. 本机路径硬编码 → 环境变量可覆盖

**现象**：`application.yml`/`application-location.yml` 里的 node 路径、driver 路径、tmp/deploy 目录写死本机路径，换机器/环境要改。

**解决**：改 `${ENV_VAR:default}` 形式，默认值保留，环境变量可覆盖：
```yaml
codegen:
  file:
    tmp-root: ${CODEGEN_TMP_ROOT:D:\IDEAproject\xiaoyu_zerocode\tmp}
screenshot:
  driver-path: "${SCREENSHOT_DRIVER_PATH:C:\\Users\\莫凡毅\\.cache\\...}"
```
注意：Windows 路径里反斜杠在 yml 双引号中要转义（`\\`），SnakeYAML 会解析回单反斜杠（已实测验证）。

---

## 十一、并发与线程规范

### 63. 建线程优先用虚拟线程；写代码要考虑并发问题

**规范**：

1. **需要额外线程时，优先用 JDK 21 虚拟线程 `Thread.ofVirtual()`，不要 `new Thread(...)`。** 虚拟线程创建成本极低、不占平台线程，适合大量短生命周期、阻塞型任务（如读进程 stdout/stderr、IO 等待），且 `join(3000)` 超时语义不变：
   ```java
   // 优先：虚拟线程
   Thread t = Thread.ofVirtual().name("task").start(() -> doSomething());
   t.join(3000);
   // 避免：每任务 new Thread，并发量大时有创建开销且无法优雅回收
   // Thread t = new Thread(() -> doSomething()); t.start();
   ```

2. **有"复用/限流"需求才用 Spring 管理的 `ThreadPoolExecutor` Bean（见 #52），且注意两个坑：**
   - 拒绝策略是 CallerRunsPolicy 时会回退到调用线程执行（可能阻塞调用方）；
   - 池线程被长阻塞 I/O（如 `readLine()` 等进程输出）占满时会**饿死排队任务**——不要把读进程输出这类长阻塞任务塞进小池子。

3. **并发场景主动检查共享资源竞争：**
   - 同一文件/目录并发写会互相覆盖（如同一 appId 的 `dist`/`node_modules`），要按资源加锁或信号量限制并发；
   - 非线程安全的容器（`StringBuilder`/`ArrayList` 等）跨线程共享，必须保证 happens-before（`join`/`Future.get`/同步器）后再读，否则有数据竞争；
   - 超时/异常路径要收尾：杀进程后要 join 读线程或确保流被关闭，避免后台线程残留。

**背景**：VueBuildService 曾用 `new Thread` 读 npm 进程 stdout/stderr；高并发下改成虚拟线程更稳，且明确不使用共享的 vue 线程池（见 #52 讨论，读进程输出这种长阻塞任务会饿死池里的生成任务）。

---

## 十二、AI 输出透传：思考过程与 token 用量

### 64. Spring AI 1.1.8 用 `chatModel.stream()` 透出 reasoning_content；`call()` 不透出

**现象**：想让前端流式展示 AI 思考过程 + token 用量，但 `chatModel.call()` 拿不到思考内容。

**原因**（反编译 spring-ai-openai 1.1.8 确认）：
- DTO 层解析 `reasoning_content` → `ChatCompletionMessage.reasoningContent`；
- **只有流式路径** `OpenAiChatModel` 的 `lambda$internalStream$5` 会把它写进 AssistantMessage 的 metadata（键 `"reasoningContent"`）；非流式 `buildGeneration` 只放 audioId/logprobs，不透出；
- 流式请求会设置 `stream_options.include_usage`，所以 `done` 最后一份 chunk 的 metadata 带 usage。

**解决**：`VueProjectAgent.runToolLoop` 把 `chatModel.call()` 换成自写的 `streamAndAggregate()`（`chatModel.stream()` + `collectList().block()`），逐 chunk：只拼**非工具调用块**的文本（避免工具参数混入 content）、从 `out.getMetadata().get("reasoningContent")` 转发思考增量、保留最后一份 metadata 供 usage 累加。SSE 新增 `reasoning` 事件（逐段文本增量）。**实测**：一次真实生成 540 条 reasoning 事件、agent_progress 逐文件正常、done 带 `totalTokens`，核心生成流程未回归。

**注意**：
- 框架会在 stream 内部自动执行工具（writeFile/finish），agent_progress 仍由工具回调触发，本改动不破坏该链路；
- 若某 provider 不返回 usage，`promptTokens/completionTokens` 为空即可，前端按 totalTokens 存在才展示。

### 65. deepseek-v4-flash 的 max_tokens 上限已不是 8192（#34 过时）

**现象**：工具调用参数 JSON 在 `content` 字段中途截断（约 24KB 处缺收尾引号），Jackson 解析失败导致整次生成失败。

**原因**：`deepseek-v4-flash` 是推理模型，**推理 token 也占输出预算**，`max-tokens: 8192` 下推理+正文不够用；deepseek-v4 这一代上限已提升。

**解决**：
1. `application.yml` 的 `max-tokens` 从 8192 提到 **32768**（实测 beta 接口接受 16384 与 32768，均 HTTP 200；可视化修改/生成本身会"过度思考"烧掉大量输出预算，32768 才能给"推理 + 工具调用 + 大文件写回"留足空间）；
2. `VueProjectAgent.runToolLoop` 给 `chatModel` 调用加**异常兜底**：参数截断/解析失败时提示模型拆小文件重试（每次 writeFile 内容 ≤3000 字符），连续 3 次异常才终止，避免整次生成失败。

**背景**：#34 记录的"max_tokens 上限 8192 需 beta 接口"只对当时的 deepseek 模型成立，现模型已支持更高。

### 66. 图片搜索"失败"是 Pexels 鉴权前缀写错 + 生成慢是推理烧时（实测打点）

**现象**：多文件流式生成越到后面越卡甚至卡死（实测两次都 >5min 撞 SSE 300s 超时 `AsyncRequestTimeoutException`，done/历史发不出去）；页面 0 张图、全程无图片报错。

**根因 1 —— 图片**：`PexelsClient` 发的是 `Authorization: Bearer <key>`，而 **Pexels 要求 `Authorization: <key>`（不带 Bearer）** → 返回 `401 Invalid API key`；旧代码不检查状态码、静默返回空列表 → searchImage 告诉模型"未找到图" → 模型按 prompt 兜底用纯色/渐变 → 0 张图且无日志。
**修复**：去掉 `"Bearer "` 前缀 + `searchImages` 检查 HTTP 状态码，非 2xx 打 `warn`（不再静默）。实测 searchImage 恢复正常，产物 scan 到 8 个图 URL。

**根因 2 —— 慢**：`deepseek-v4-flash` 是推理模型，工具循环每轮都先产出大量 reasoning 再调用工具。加打点后实测：**一个小 html 单次流式调用 reasoning≈9228 字符、耗时 136s**；多文件轮次更多 → 总时长超 5 分钟。叠加框架 tool-loop 每轮重发含全部已写文件的完整历史，上下文越滚越大。
**修复（本轮）**：
- `AiChatController` 各 SSE `SseEmitter` 超时 300s → **600s**（慢生成不再被掐断）；
- `VueProjectAgent` 加打点：`[agent] 单次流式调用完成：reasoning=?字符 text=?字符 耗时=?ms`、`tool-loop 结束 … 总耗时=?ms`、`[searchImage] 被调用/无结果`，后续可据此量化 A1(推理) vs A2(上下文) 占比。

**待定方向（未做，需确认）**：prompt 抑制过度推理 / 工具循环换更快模型 / 自管 tool-loop 裁剪历史（根治上下文膨胀）。

**注意**：前端 reasoning 曾用 `MarkdownView` 对整串增量全量重渲染导致 O(n²) 卡死，已改为"本地 buffer 攒、完成时一次性赋值 + 纯文本折叠展示"。

---

## 十三、生成任务的取消与状态回收（P0 修复实录）

### 67. 取消生成不要用 `Thread#interrupt()`

**现象**：给"停止生成"接上 `task.cancel()` → `worker.interrupt()` 后，被停止的请求返回**裸 Tomcat 500 页面**（不是统一 JSON 错误）；日志出现 `RedisCommandInterruptedException: Command interrupted` 与 `ClosedByInterruptException`。

**原因**：生成线程是 **Tomcat 工作线程**（非流式接口直接跑在请求线程上）或共享池线程。对它 `interrupt()` 会：
1. 打断该线程上正在进行的 **Redis 命令**（Spring Session 读、ChatMemory 写）与 **COS 上传**（`ClosedByInterruptException`）；
2. 把**中断标志**残留到后续错误处理路径，连渲染 `BaseResponse` 都可能二次抛异常 → 最终落到容器默认错误页。

**解决**：取消**只置标志**，在副作用边界（`writeFile`）与**模型调用返回后**检查并抛业务异常。代价是响应延迟 = 一次模型调用的时长，可接受。

**经验**：**不要中断不属于你创建的线程**（Tomcat / 线程池线程）。取消共享线程上的任务，用协作式标志，而不是 `Thread.interrupt()`。

### 68. 工具回调里抛异常无法终止生成（会被框架吞掉）

**现象**：第一版把取消检查放进 `writeFile` 工具，期望抛异常终止生成；实测**点了停止后生成仍在继续**（跑了 90s+ 不停），只是那个文件没写成功。

**原因**：Spring AI 框架在流内自动执行工具时，会**捕获工具抛出的异常并作为"工具错误结果"喂回模型**（让模型自己纠错），异常不会冒泡到调用方。模型看到工具报错后继续重试，生成永不结束。

**解决**：工具里**不抛异常**——检测到已取消就返回"生成已被用户停止，请调用 finish 结束"；真正的终止放在 **`runToolLoop` 中模型调用返回之后**（`taskRegistry.checkCancelled(appId)`），在那里抛出才会冒泡到门面并被记为 `stopped`。

**经验**：**工具回调是"异常被吞掉"的边界**（框架要拿工具结果继续喂模型）。任何"中止整个循环"的意图都不能只在工具里实现，必须在工具循环外层检查。

### 69. devtools 会在 `mvn test` 时热重启应用，污染端到端验证

**现象**：改完代码跑 `mvn test`，正在运行的后端**自动重启**（日志 `Restarting due to N class path changes`）；同时另一份 `mvn spring-boot:run` 启动失败，报 `Port 8124 was already in use`；验证日志里混入"重启瞬间"的连接中断（`Redis command interrupted`），容易被误读成代码 bug。

**原因**：`spring-boot-devtools` 监听 classpath，编译产物一变就重启应用；而端口仍被旧进程占用，新实例起不来。

**解决/注意**：
- 做端到端验证时关掉热重启：`-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false`；
- 判断"后端是否在跑"要看**日志**（`Started xiaoyuZeroCodeApplication`），不能只看端口——端口可能是上一个进程还在占着。

---

## 十四、异步任务与验证方法论

### 70. 异步任务在生成流程内部提交时抢不到互斥锁（状态恒为 skipped）

**现象**：把渲染修复改成异步后，检测明明发现了问题（3 档视口各 2 处图裂），但 `/ai/render-fix/status` 立刻返回 **`skipped`**，修复从来没跑过。

**原因**：异步任务是在**生成流程内部**（`verifyAndScheduleFix` 里）提交的。那一刻生成任务**自己还持有** appId 的互斥（`GenerationTaskRegistry` 里的任务要到生成方法的 `finally` 才释放）。异步线程一启动就 `tryAcquire`，必然被自己人挡住 → 直接判为"已有任务在跑"跳过。

**解决**：让异步修复**短暂重试抢占**（5s 内每 200ms 一次），等生成释放后再抢；若用户紧接着发起新一轮生成则超时放弃——这正是期望的互斥行为。

**经验**：**在"持有锁的流程内部"提交异步任务时，异步侧不能立刻抢同一把锁**。要么延后提交（流程结束再提交），要么让异步侧带重试/等待。这类 bug 的伪装性极强——状态看着正常（`skipped` 是个"合法值"），只有对着真实链路验证才会暴露。

### 71. 验证渲染问题不能靠随机 prompt，要用确定性触发

**现象**：想验证"渲染问题 → 自动修复"这条回路，连跑两次图片密集的 prompt，模型输出的页面**都是干净的**（0 图裂），修复分支一次都没触发。

**原因**：生成结果本身是概率性的。靠"这个 prompt 容易出问题"去撞，既不稳定、又不可复现。

**解决**：改用**确定性触发**——让 prompt 明确要求引用一个**不存在的本地图片路径**（`<img src="images/hero-banner.jpg">` 且不许用外链）。文件不存在 → 必然图裂，检测必然命中。实测三次尝试后一次命中：三档视口各 10 处图裂 → 修复 → 复验 0 处。

**经验**：**要验证"异常分支"，就得构造必然异常**，别指望随机输入去撞。可用的确定性触发手法：引用不存在的资源、故意写超宽元素（横向溢出）、把 body 内容清空（空白页判定）。
