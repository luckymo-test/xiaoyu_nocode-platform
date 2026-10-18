package com.mofanyi.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 调用指标收集器：只打低基数标签（model / call_type / status / kind / error_type）。
 * 高基数维度（用户/应用/内容/错误原文）走 MySQL 明细，见 AiCallListener 与 ai_call_log。
 * 指标：
 * - ai_call_total{model,call_type,status}
 * - ai_call_duration_seconds{model,call_type}（Histogram，可算 P50/P90/P95/P99）
 * - ai_tokens_total{model,call_type,kind=input|output|total}
 * - ai_call_errors_total{model,call_type,error_type}
 */
@Component
public class AiMetricsRecorder {

    /**
     * 耗时 SLO 桶：AI 调用跨度从秒到分钟，默认桶不够
     */
    private static final Duration[] DURATION_BUCKETS = {
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(60), Duration.ofSeconds(120)
    };

    private final MeterRegistry registry;
    private final String model;
    private final int maxOutputTokens;

    public AiMetricsRecorder(MeterRegistry registry,
                             @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
                             @Value("${spring.ai.openai.chat.options.max-tokens:0}") int maxOutputTokens) {
        this.registry = registry;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public String model() {
        return model;
    }

    /**
     * 记录一次 AI 调用（成功/失败都调）
     */
    public void record(String callType, String status, long inputTokens, long outputTokens,
                       long durationMs, String reasoningLevel) {
        String level = reasoningLevel == null || reasoningLevel.isBlank() ? "standard" : reasoningLevel;
        Counter.builder("ai_call_total")
                .tag("model", model).tag("call_type", callType).tag("status", status)
                .tag("reasoning_level", level)
                .register(registry).increment();

        Timer.builder("ai_call_duration_seconds")
                .tag("model", model).tag("call_type", callType)
                .tag("reasoning_level", level)
                .publishPercentileHistogram()
                .serviceLevelObjectives(DURATION_BUCKETS)
                .register(registry)
                .record(Duration.ofMillis(Math.max(durationMs, 0)));

        if (inputTokens > 0) {
            tokens(callType, "input", inputTokens, level);
        }
        if (outputTokens > 0) {
            tokens(callType, "output", outputTokens, level);
        }
        if (inputTokens > 0 || outputTokens > 0) {
            tokens(callType, "total", inputTokens + outputTokens, level);
        }
    }

    /**
     * 记录一次失败（按归一化的 error_type 分类）
     */
    public void recordError(String callType, String errorType, String reasoningLevel) {
        String level = reasoningLevel == null || reasoningLevel.isBlank() ? "standard" : reasoningLevel;
        Counter.builder("ai_call_errors_total")
                .tag("model", model).tag("call_type", callType).tag("error_type", errorType)
                .tag("reasoning_level", level)
                .register(registry).increment();
    }

    private void tokens(String callType, String kind, long value, String reasoningLevel) {
        Counter.builder("ai_tokens_total")
                .tag("model", model).tag("call_type", callType).tag("kind", kind)
                .tag("reasoning_level", reasoningLevel)
                .register(registry).increment(value);
    }

    /**
     * 把异常归类到有限集合（保持低基数）：network / truncation / rate_limit / auth / other
     */
    public static String classify(Throwable e) {
        if (e == null) {
            return "other";
        }
        String msg = (e.getMessage() == null ? "" : e.getMessage()).toLowerCase();
        String type = e.getClass().getSimpleName().toLowerCase();
        String all = type + " " + msg;
        if (all.contains("timeout") || all.contains("connection") || all.contains("socket")
                || all.contains("reset") || all.contains("unreachable")) {
            return "network";
        }
        if (all.contains("truncat") || all.contains("unexpected end-of-input")
                || all.contains("json") || all.contains("parse")) {
            return "truncation";
        }
        if (all.contains("429") || all.contains("rate limit") || all.contains("too many")) {
            return "rate_limit";
        }
        if (all.contains("401") || all.contains("403") || all.contains("unauthorized")
                || all.contains("invalid api key") || all.contains("authentication")) {
            return "auth";
        }
        return "other";
    }
}
