package com.mofanyi.ai.tools;

import com.mofanyi.core.ChunkedFileWriter;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大文件顺序分块写入工具，单片内容建议 4,000～8,000 字符。
 */
@Component
public class WriteFileChunkTool {

    public record WriteFileChunkRequest(
            String path,
            int chunkIndex,
            String content,
            boolean lastChunk) {
    }

    private final ChunkedFileWriter writer;

    public WriteFileChunkTool(ChunkedFileWriter writer) {
        this.writer = writer;
    }

    public ToolCallback build(CodeGenTypeEnum type, Long appId, Map<String, String> files,
                              AgentProgressListener listener) {
        return FunctionToolCallback.builder("writeFileChunk", (WriteFileChunkRequest request) -> {
            ChunkedFileWriter.Result result = writer.append(
                    type, appId, request.path(), request.chunkIndex(), request.content(),
                    request.lastChunk(), files, listener);
            return (result.accepted() ? "OK " : "ERROR ")
                    + result.message() + "；nextChunkIndex=" + result.nextChunkIndex();
        })
                .description("顺序写入大文件的单个分片。path 固定；chunkIndex 从 0 开始严格递增；"
                        + "每片建议 4000～8000 字符，最多 12000；最后一片 lastChunk=true。"
                        + "收到 OK 后再发送下一片，不可并行或跳号。")
                .inputType(WriteFileChunkRequest.class)
                .build();
    }
}
