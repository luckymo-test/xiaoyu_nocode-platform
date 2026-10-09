package com.mofanyi.ai.tools;

import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.core.ToolRiskRegistry;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * writeFile 工具：向工程写一个源文件（完整内容覆盖），并上报进度。
 */
@Component
public class WriteFileTool {

    public record WriteFileRequest(String path, String content) {
    }

    private final CodeFileWriteService codeFileWriteService;
    private final GenerationTaskRegistry taskRegistry;
    private final ToolRiskRegistry toolRiskRegistry;

    public WriteFileTool(CodeFileWriteService codeFileWriteService, GenerationTaskRegistry taskRegistry,
                         ToolRiskRegistry toolRiskRegistry) {
        this.codeFileWriteService = codeFileWriteService;
        this.taskRegistry = taskRegistry;
        this.toolRiskRegistry = toolRiskRegistry;
    }

    public ToolCallback build(CodeGenTypeEnum type, Long appId, Map<String, String> files,
                              AgentProgressListener listener) {
        return FunctionToolCallback.builder("writeFile", (WriteFileRequest req) -> {
            // 已请求停止：不再落盘，并提示模型收尾。
            // 注意不能用抛异常：工具异常会被框架吞成"工具错误结果"喂回模型，反而让模型继续重试。
            if (taskRegistry.isCancelled(appId)) {
                return "生成已被用户停止，请立即调用 finish 结束，不要再写入任何文件。";
            }
            ToolPaths.validate(req.path());
            // 工具风险分级：写文件的大小上限（plan-agent-engineering 第四章）
            toolRiskRegistry.checkWriteContent("writeFile", req.content());
            if (req.content() == null) {
                return "内容为空，未写入";
            }
            codeFileWriteService.saveProjectFile(type, appId, req.path(), req.content());
            files.put(req.path(), req.content());
            if (listener != null) {
                listener.onFileWritten(req.path(), req.content().length(), req.content());
            }
            return "已写入 " + req.path() + "（" + req.content().length() + " 字符）";
        })
                .description("向工程写入一个文件。path 为相对路径，content 为文件完整内容。一次只写一个文件，写完再写下一个。")
                .inputType(WriteFileRequest.class)
                .build();
    }
}
