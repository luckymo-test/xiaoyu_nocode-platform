# Tool Calling 截断与分块写入

> 日期：2026-10-25
> 状态：已实现

## 问题

模型在单次 `writeFile` 中返回超大文件时，Tool Call JSON 可能在 `content` 中途结束。
Spring AI 在反序列化 `WriteFileRequest` 时抛出 `JsonEOFException`，框架内部工具执行失败。

应用层 200,000 字符上限此时还没有执行，因此单纯调高该上限不能解决问题。

## 容错

- 默认启用自管 Tool-Calling Loop；
- 参数截断被归类为 `TOOL_ARGUMENTS_TRUNCATED`；
- 自管循环把结构化错误返回给模型，而不是终止整个模型流；
- 提示模型保留已完成文件，缩小当前文件，或改用分块写入；
- 连续失败仍有上限，避免无限重试。

## 页面拆分

- 单文件建议不超过约 12,000 字符；
- HTML 可拆 CSS 和 `js/modules/*.js`；
- Vue/React/Next 按页面区块拆组件，复杂状态拆 hooks 或模块；
- 禁止通过重复 `writeFile` 覆盖同一路径来模拟追加。

## 分块写入

新增 `writeFileChunk`：

```json
{
  "path": "src/App.tsx",
  "chunkIndex": 0,
  "content": "前 4000～8000 字符",
  "lastChunk": false
}
```

规则：

- `path` 全程不变；
- `chunkIndex` 从 0 严格递增；
- 单片最多 12,000 字符；
- 重复发送相同分片是幂等的；
- 乱序或同索引不同内容会被拒绝；
- 分片保存在 `.agent/chunk-writes`；
- 只有最后一片到达后才校验总大小并提交正式文件；
- 失败和取消不会留下半个正式源码文件。

## 模型流中断与时间预算

- 模型流超过 `codegen.agent.stream-idle-timeout-seconds` 没有新 chunk 时主动断开，避免 HTTP/2 长连接静默挂起。
- `RST_STREAM`、stream reset、连接重置和 429/5xx 按瞬态传输错误处理，采用短退避后重试，不向对话注入无意义的工具恢复提示。
- Agent 墙钟预算随推理档位配置：fast 10 分钟、eco 15 分钟、standard 20 分钟、max 25 分钟。
- 任务级异常分类会沿 cause 链识别流重置，因此内层重试耗尽后仍可由 Job 队列按 `max-attempts` 重试。
