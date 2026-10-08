package com.mofanyi.ai.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * finish 工具：结束生成，携带整体概述。
 */
@Component
public class FinishTool {

    public record FinishRequest(String description) {
    }

    public ToolCallback build(AtomicBoolean finished, AtomicReference<String> description) {
        return FunctionToolCallback.builder("finish", (FinishRequest req) -> {
            description.set(req.description() == null ? "" : req.description());
            finished.set(true);
            return "已结束生成";
        })
                .description("所有文件写入完成后调用本工具结束生成。description 为工程整体概述（2~3 句话说明结构与功能）。")
                .inputType(FinishRequest.class)
                .build();
    }
}
