package com.mofanyi.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PublicErrorMessageTest {

    @Test
    @DisplayName("npm/path/stack 等内部信息不会进入用户文案")
    void hidesDependencyInternals() {
        PublicErrorMessage.Message message = PublicErrorMessage.from(
                "BusinessException",
                "生成 package-lock.json 失败：npm error\\n"
                        + "D:\\tmp\\sandbox\\build\\debug.log\\n"
                        + "TypeError: Class extends value undefined");

        assertEquals("DEPENDENCY_SETUP_FAILED", message.code());
        assertEquals("生成依赖准备失败，请稍后重试", message.text());
        assertFalse(message.text().contains("D:\\"));
        assertFalse(message.text().contains("npm"));
    }

    @Test
    @DisplayName("轮数、构建、模型服务失败使用各自可执行提示")
    void classifiesKnownFailures() {
        assertEquals("STEP_LIMIT_EXCEEDED",
                PublicErrorMessage.from("MAX_STEPS_EXCEEDED", "").code());
        assertEquals("BUILD_VALIDATION_FAILED",
                PublicErrorMessage.from("BUILD", "vite build failed").code());
        assertEquals("MODEL_SERVICE_UNAVAILABLE",
                PublicErrorMessage.from("MODEL", "deepseek timeout 503").code());
    }
}
