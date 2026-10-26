package com.mofanyi.ai.tools;

import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * readFile 工具：读取工程某文件的完整内容（供修改/自检按需读精确源码，避免全量塞入上下文）。
 */
@Component
public class ReadFileTool {

    public record ReadFileRequest(String path) {
    }

    private final CodeFileWriteService codeFileWriteService;

    public ReadFileTool(CodeFileWriteService codeFileWriteService) {
        this.codeFileWriteService = codeFileWriteService;
    }

    public ToolCallback build(CodeGenTypeEnum type, Long appId) {
        return FunctionToolCallback.builder("readFile", (ReadFileRequest req) -> {
            ToolPaths.validate(req.path());
            String content = codeFileWriteService.readFile(
                    codeFileWriteService.getProjectDir(type, appId), req.path());
            return content == null
                    ? "文件不存在: " + req.path() + "（请先看文件清单确认正确相对路径）"
                    : "【" + req.path() + " sha256=" + sha256(content) + "】\n" + content;
        })
                .description("读取工程中某个源文件的完整内容。path 为相对工程根目录的路径（如 index.html、css/styles.css、js/app.js）。修改前应先读取目标文件再改动。")
                .inputType(ReadFileRequest.class)
                .build();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算文件摘要失败", e);
        }
    }
}
