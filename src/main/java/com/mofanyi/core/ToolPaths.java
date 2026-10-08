package com.mofanyi.core;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;

/**
 * 生成 agent 工具通用工具：路径安全校验。
 */
public final class ToolPaths {

    private ToolPaths() {
    }

    /**
     * 校验文件路径安全：必须是相对路径，禁止绝对路径、路径穿越、盘符
     */
    public static void validate(String path) {
        ThrowUtils.throwIf(path == null || path.isBlank(), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        ThrowUtils.throwIf(path.startsWith("/") || path.startsWith("\\") || path.matches("^[a-zA-Z]:.*"),
                ErrorCode.PARAMS_ERROR, "文件路径必须是相对路径");
        ThrowUtils.throwIf(path.contains(".."), ErrorCode.PARAMS_ERROR, "文件路径非法");
    }
}
