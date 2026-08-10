# 开发问题与解决方案记录

> 小玉零代码平台（xiaoyu_zerocode）开发过程中遇到的问题与解决方案。
> 按主题分类，每条包含：现象 → 原因 → 解决。
> 第一~六章为后端（Spring Boot + Spring AI + MyBatis-Flex），第七章为前端（Vue 3 + Ant Design Vue）。

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

---

## 排查经验总结

- **50000 系统错误** 先看 IDEA 控制台完整堆栈（`GlobalExceptionHandler` 会打 `log.error("RuntimeException", e)`），比猜快得多。
- **接口 50000 先排除中文编码**（Windows curl 常见），用英文 JSON 复测能快速区分"业务 bug"和"编码问题"。
- **改代码后要重启应用** 才生效（`mvn compile` 只验证编译，运行时还是旧 class）。
- **cookie 文件** 用 `-c 保存 -b 发送`，注意文件落在当前工作目录。
- **雪花 ID 类接口返回 40400/查不到** 先怀疑前端 `Number()` 精度丢失，排查是否按字符串传 ID。
- **Vue 里"数据变了但界面不刷新"** 优先怀疑响应式丢失（普通对象/非 reactive 赋值、ref 模板解包误用 `.value`）。
- **流式/打字机"看不到中间过程"** 用密集采样（`setInterval` 每 400ms 记录 DOM 状态）确认是数据没到还是渲染被合并，再对症下药。
- **`Connection reset` / `Connection closed`（AI 流式）** 多为 DeepSeek 服务端/网络偶发断连（搜索确认：网关 600s 上限、高峰期主动断开），非代码 bug。防御：后端 error 分支先用已累积 token 容错解析兜底，前端把网络错误映射为"网络连接不稳定，请重试"友好文案。**注意** beta 接口（`/beta`）才能支持 max_tokens 8192，普通接口只认 4096。
