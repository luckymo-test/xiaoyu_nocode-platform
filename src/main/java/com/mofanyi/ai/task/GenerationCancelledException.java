package com.mofanyi.ai.task;

import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;

/**
 * 生成被手动停止时抛出，用于区分"用户主动停止"与"生成失败"（两者状态不同）。
 */
public class GenerationCancelledException extends BusinessException {

    private final Long appId;

    public GenerationCancelledException(Long appId) {
        super(ErrorCode.OPERATION_ERROR, "生成已被手动停止");
        this.appId = appId;
    }

    public Long getAppId() {
        return appId;
    }
}
