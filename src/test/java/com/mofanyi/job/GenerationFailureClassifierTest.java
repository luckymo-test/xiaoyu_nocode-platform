package com.mofanyi.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationFailureClassifierTest {

    private final GenerationFailureClassifier classifier = new GenerationFailureClassifier();

    @Test
    @DisplayName("429/超时/连接重置可重试，参数和版本冲突不重试")
    void classifiesTransientFailures() {
        assertTrue(classifier.retryable(new RuntimeException("HTTP 429 rate limit")));
        assertTrue(classifier.retryable(new RuntimeException("request timeout")));
        assertTrue(classifier.retryable(new RuntimeException("Connection reset")));
        assertTrue(classifier.retryable(new RuntimeException(
                "response failed", new java.io.IOException("Received RST_STREAM: Internal error"))));
        assertTrue(classifier.retryable(new RuntimeException(
                "流式调用失败", new IllegalStateException("stream reset by peer"))));
        assertFalse(classifier.retryable(new IllegalArgumentException("参数错误")));
        assertFalse(classifier.retryable(new IllegalStateException("当前版本已被其他操作更新")));
    }
}
