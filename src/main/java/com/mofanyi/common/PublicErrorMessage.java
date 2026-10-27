package com.mofanyi.common;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * Converts internal generation failures into stable, user-safe messages.
 * Raw exception text remains in the job, dead-letter and application logs.
 */
public final class PublicErrorMessage {

    public record Message(String code, String text) {
    }

    private PublicErrorMessage() {
    }

    public static Message from(String errorType, String internalMessage) {
        String value = (StrUtil.blankToDefault(errorType, "") + " "
                + StrUtil.blankToDefault(internalMessage, ""))
                .toLowerCase(Locale.ROOT);
        if (value.contains("cancel") || value.contains("取消")) {
            return new Message("CANCELLED", "生成任务已取消");
        }
        if (value.contains("max_steps") || value.contains("最大工具轮数")) {
            return new Message("STEP_LIMIT_EXCEEDED",
                    "生成步骤超过上限，请简化需求或提高推理强度后重试");
        }
        if (value.contains("finish_required") || value.contains("未调用 finish")) {
            return new Message("INCOMPLETE_GENERATION",
                    "生成过程未正常结束，请重试");
        }
        if (value.contains("package-lock") || value.contains("npm")
                || value.contains("dependency") || value.contains("依赖")) {
            return new Message("DEPENDENCY_SETUP_FAILED",
                    "生成依赖准备失败，请稍后重试");
        }
        if (value.contains("build") || value.contains("compile")
                || value.contains("vite") || value.contains("typescript")
                || value.contains("构建") || value.contains("编译")) {
            return new Message("BUILD_VALIDATION_FAILED",
                    "生成项目构建未通过，请重试");
        }
        if (value.contains("sandbox") || value.contains("沙箱")
                || value.contains("path traversal") || value.contains("permission")
                || value.contains("越权") || value.contains("路径")) {
            return new Message("SANDBOX_VALIDATION_FAILED",
                    "生成环境校验失败，请重试");
        }
        if (value.contains("model") || value.contains("deepseek")
                || value.contains("token") || value.contains("stream")
                || value.contains("429") || value.contains("502")
                || value.contains("503") || value.contains("504")) {
            return new Message("MODEL_SERVICE_UNAVAILABLE",
                    "模型服务暂时不可用，请稍后重试");
        }
        if (value.contains("timeout") || value.contains("超时")) {
            return new Message("GENERATION_TIMEOUT",
                    "生成响应超时，请稍后重试");
        }
        return new Message("GENERATION_FAILED",
                "生成任务执行失败，请稍后重试");
    }
}
