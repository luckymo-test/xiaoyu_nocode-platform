package com.mofanyi.core;

import com.mofanyi.entity.AiCallLog;
import com.mofanyi.mapper.AiCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * 明细落库线程池：虚拟线程，短任务，不阻塞生成
     */
    private final ExecutorService detailWriter = Executors.newVirtualThreadPerTaskExecutor();

    public AiCallListener(AiMetricsRecorder metrics, AiCallLogMapper aiCallLogMapper) {
        this.metrics = metrics;
        this.aiCallLogMapper = aiCallLogMapper;
    }

    /**
     * 调用成功
     */
    public void onSuccess(AiCallContext ctx, String finishReason,
                          long inputTokens, long outputTokens, long durationMs) {
        metrics.record(ctx.callType(), "success", inputTokens, outputTokens, durationMs);
        AiCallLog row = AiCallLog.builder()
                .appId(ctx.appId()).userId(ctx.userId()).callType(ctx.callType())
                .model(metrics.model()).maxOutputTokens(metrics.maxOutputTokens())
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
        String errorType = AiMetricsRecorder.classify(error);
        metrics.record(ctx.callType(), "error", 0, 0, durationMs);
        metrics.recordError(ctx.callType(), errorType);
        AiCallLog row = AiCallLog.builder()
                .appId(ctx.appId()).userId(ctx.userId()).callType(ctx.callType())
                .model(metrics.model()).maxOutputTokens(metrics.maxOutputTokens())
                .durationMs(durationMs).status("error")
                .errorMsg(truncate(error == null ? null : error.getMessage()))
                .createTime(LocalDateTime.now())
                .build();
        insertAsync(row);
    }

    private void insertAsync(AiCallLog row) {
        detailWriter.submit(() -> {
            try {
                aiCallLogMapper.insert(row);
            } catch (RuntimeException e) {
                // 明细落库失败不影响主链路
                log.warn("AI 调用明细落库失败, appId={}, callType={}", row.getAppId(), row.getCallType(), e);
            }
        });
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
