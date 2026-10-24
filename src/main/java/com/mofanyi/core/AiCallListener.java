package com.mofanyi.core;

import com.mofanyi.entity.AiCallLog;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.mapper.AiCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * AI 调用监听器：每次模型调用结束时统一"打指标 + 异步落明细"。
 * - 指标走 AiMetricsRecorder（低基数，Prometheus）
 * - 明细走 ai_call_log（高基数：用户/应用/错误，异步写，不阻塞生成主链路）
 */
@Slf4j
@Component
public class AiCallListener {

    private final AiMetricsRecorder metrics;
    private final AiCallLogMapper aiCallLogMapper;
    private final ModelExecutionTrace modelExecutionTrace;

    /**
     * 明细落库执行器：虚拟线程、短任务，不阻塞生成。
     * 由 Spring 容器管理（ThreadPoolConfig#aiCallDetailExecutor），不再自行 new（项目规范 #63）。
     */
    private final ExecutorService detailWriter;

    public AiCallListener(AiMetricsRecorder metrics, AiCallLogMapper aiCallLogMapper,
                          ModelExecutionTrace modelExecutionTrace,
                          @Qualifier("aiCallDetailExecutor") ExecutorService detailWriter) {
        this.metrics = metrics;
        this.aiCallLogMapper = aiCallLogMapper;
        this.modelExecutionTrace = modelExecutionTrace;
        this.detailWriter = detailWriter;
    }

    /**
     * 调用成功
     */
    public void onSuccess(AiCallContext ctx, String finishReason,
                          long inputTokens, long outputTokens, long durationMs) {
        onSuccess(ctx, finishReason, inputTokens, outputTokens, durationMs, "standard");
    }

    public void onSuccess(AiCallContext ctx, String finishReason,
                          long inputTokens, long outputTokens, long durationMs, String reasoningLevel) {
        onSuccess(ctx, finishReason, inputTokens, outputTokens, durationMs,
                reasoningLevel, "configured", metrics.model(), "legacy");
    }

    public void onSuccess(AiCallContext ctx, String finishReason,
                          long inputTokens, long outputTokens, long durationMs,
                          String reasoningLevel, String provider,
                          String actualModel, String modelTask) {
        onSuccess(ctx, finishReason, inputTokens, outputTokens, durationMs,
                reasoningLevel, provider, actualModel, actualModel, modelTask);
    }

    public void onSuccess(AiCallContext ctx, String finishReason,
                          long inputTokens, long outputTokens, long durationMs,
                          String reasoningLevel, String provider,
                          String actualModelId, String actualModelName, String modelTask) {
        metrics.record(ctx.callType(), "success", inputTokens, outputTokens,
                durationMs, reasoningLevel, provider, actualModelName, modelTask);
        modelExecutionTrace.recordCall(ctx, modelTask, "success", provider,
                actualModelId, actualModelName, reasoningLevel, inputTokens, outputTokens,
                durationMs, finishReason);
        AiCallLog row = AiCallLog.builder()
                .appId(ctx.appId()).jobId(ctx.jobId())
                .userId(ctx.userId()).callType(ctx.callType())
                .provider(provider).modelTask(modelTask)
                .model(actualModelName == null || actualModelName.isBlank()
                        ? metrics.model() : actualModelName)
                .maxOutputTokens(metrics.maxOutputTokens())
                .inputTokens((int) inputTokens).outputTokens((int) outputTokens)
                .totalTokens((int) (inputTokens + outputTokens))
                .durationMs(durationMs).finishReason(finishReason)
                .status("success").createTime(LocalDateTime.now())
                .build();
        insertAsync(row);
    }

    /**
     * 调用失败
     */
    public void onFailure(AiCallContext ctx, Throwable error, long durationMs) {
        onFailure(ctx, error, durationMs, "standard");
    }

    public void onFailure(AiCallContext ctx, Throwable error, long durationMs, String reasoningLevel) {
        onFailure(ctx, error, durationMs, reasoningLevel,
                "configured", metrics.model(), "legacy");
    }

    public void onFailure(AiCallContext ctx, Throwable error, long durationMs,
                          String reasoningLevel, String provider,
                          String actualModel, String modelTask) {
        onFailure(ctx, error, durationMs, reasoningLevel, provider,
                actualModel, actualModel, modelTask);
    }

    public void onFailure(AiCallContext ctx, Throwable error, long durationMs,
                          String reasoningLevel, String provider,
                          String actualModelId, String actualModelName, String modelTask) {
        String errorType = AiMetricsRecorder.classify(error);
        metrics.record(ctx.callType(), "error", 0, 0, durationMs, reasoningLevel,
                provider, actualModelName, modelTask);
        metrics.recordError(ctx.callType(), errorType, reasoningLevel,
                provider, actualModelName, modelTask);
        modelExecutionTrace.recordCall(ctx, modelTask, "error", provider,
                actualModelId, actualModelName, reasoningLevel, 0, 0,
                durationMs, null);
        AiCallLog row = AiCallLog.builder()
                .appId(ctx.appId()).jobId(ctx.jobId())
                .userId(ctx.userId()).callType(ctx.callType())
                .provider(provider).modelTask(modelTask)
                .model(actualModelName == null || actualModelName.isBlank()
                        ? metrics.model() : actualModelName)
                .maxOutputTokens(metrics.maxOutputTokens())
                .durationMs(durationMs).status("error")
                .errorMsg(truncate(error == null ? null : error.getMessage()))
                .createTime(LocalDateTime.now())
                .build();
        insertAsync(row);
    }

    private void insertAsync(AiCallLog row) {
        try {
            detailWriter.submit(() -> {
                try {
                    aiCallLogMapper.insert(row);
                } catch (RuntimeException e) {
                    // 明细落库失败不影响主链路
                    log.warn("AI 调用明细落库失败, appId={}, callType={}", row.getAppId(), row.getCallType(), e);
                }
            });
        } catch (RuntimeException e) {
            // 应用关闭时执行器可能已关闭，调用监控不应反过来影响生成线程退出
            log.debug("AI 调用明细执行器已关闭，跳过异步落库, appId={}", row.getAppId(), e);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
