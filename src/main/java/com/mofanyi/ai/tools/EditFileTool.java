package com.mofanyi.ai.tools;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.core.ToolRiskRegistry;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Optimistic-concurrency text edit tool. It applies bounded find/replace
 * operations and refuses to write when the file hash changed after the model
 * read it.
 */
@Component
public class EditFileTool {

    public record Edit(String find, String replace) {
    }

    public record EditFileRequest(String path, String expectedSha256, List<Edit> edits) {
    }

    private final CodeFileWriteService files;
    private final GenerationTaskRegistry taskRegistry;
    private final ToolRiskRegistry risks;

    public EditFileTool(CodeFileWriteService files,
                        GenerationTaskRegistry taskRegistry,
                        ToolRiskRegistry risks) {
        this.files = files;
        this.taskRegistry = taskRegistry;
        this.risks = risks;
    }

    public ToolCallback build(CodeGenTypeEnum type, Long appId,
                              Map<String, String> changedFiles,
                              AgentProgressListener listener) {
        return FunctionToolCallback.builder("editFile", (EditFileRequest request) -> {
            if (taskRegistry.isCancelled(appId)) {
                return "生成已被用户停止，请立即调用 finish 结束。";
            }
            ToolPaths.validate(request.path());
            ThrowUtils.throwIf(request.edits() == null || request.edits().isEmpty(),
                    ErrorCode.PARAMS_ERROR, "edits 不能为空");
            ThrowUtils.throwIf(request.edits().size() > 20,
                    ErrorCode.PARAMS_ERROR, "单次 editFile 最多 20 个替换");
            String root = files.getProjectDir(type, appId);
            String current = files.readFile(root, request.path());
            ThrowUtils.throwIf(current == null, ErrorCode.NOT_FOUND_ERROR,
                    "文件不存在，请先创建：" + request.path());
            if (StrUtil.isNotBlank(request.expectedSha256())) {
                ThrowUtils.throwIf(!sha256(current).equalsIgnoreCase(request.expectedSha256()),
                        ErrorCode.OPERATION_ERROR,
                        "VERSION_CONFLICT：文件内容已变化，请重新读取后再修改");
            }
            String updated = current;
            for (Edit edit : request.edits()) {
                ThrowUtils.throwIf(edit == null || StrUtil.isEmpty(edit.find()),
                        ErrorCode.PARAMS_ERROR, "find 不能为空");
                ThrowUtils.throwIf(updated.indexOf(edit.find()) < 0,
                        ErrorCode.PARAMS_ERROR,
                        "未找到目标片段；请重新读取文件并使用更精确的上下文");
                updated = updated.replace(edit.find(),
                        edit.replace() == null ? "" : edit.replace());
            }
            risks.checkWriteContent("editFile", updated);
            files.saveProjectFile(type, appId, request.path(), updated);
            changedFiles.put(request.path(), updated);
            if (listener != null) {
                listener.onFileWritten(request.path(), updated.length(), updated);
            }
            return "已局部修改 " + request.path() + "（" + updated.length() + " 字符）";
        })
                .description("对已有文件执行小范围 find/replace。path 为相对路径；"
                        + "expectedSha256 使用 readFile 返回的 sha256；edits 每项包含 find/replace。"
                        + "文件较大或新建文件时才使用 writeFile/writeFileChunk。")
                .inputType(EditFileRequest.class)
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
