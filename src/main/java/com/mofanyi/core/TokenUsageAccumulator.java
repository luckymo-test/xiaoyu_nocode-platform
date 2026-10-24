package com.mofanyi.core;

import org.springframework.ai.chat.model.ChatResponse;

/**
 * Token 用量累加器（plan-refactor 7.1 拆分 S3 步）。
 *
 * <p><b>刻意不做成 Spring Bean</b>：它按次持有计数（一次生成一个实例），由 {@code VueProjectAgent.generate}
 * 每次 {@code new} 出来并沿调用链传递。若做成单例 {@code @Component}，并发生成会共享同一份计数 ——
 * 与 P0 阶段那个"共享可变状态"的坑同类。
 */
public class TokenUsageAccumulator {

    private long promptTokens;
    private long completionTokens;

    /**
     * 累加单轮模型响应的 token 用量
     *
     * @param resp 单轮模型响应（取 metadata.usage）
     */
    public void accumulate(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) {
            return;
        }
        var usageMeta = resp.getMetadata().getUsage();
        if (usageMeta == null) {
            return;
        }
        if (usageMeta.getPromptTokens() != null) {
            promptTokens += usageMeta.getPromptTokens();
        }
        if (usageMeta.getCompletionTokens() != null) {
            completionTokens += usageMeta.getCompletionTokens();
        }
    }

    public void accumulate(long promptTokenCount, long completionTokenCount) {
        if (promptTokenCount > 0) {
            promptTokens += promptTokenCount;
        }
        if (completionTokenCount > 0) {
            completionTokens += completionTokenCount;
        }
    }

    public long promptTokens() {
        return promptTokens;
    }

    public long completionTokens() {
        return completionTokens;
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * 本次生成的用量快照（挂在结果 VO 上）
     */
    public TokenUsage snapshot() {
        return new TokenUsage(promptTokens, completionTokens);
    }

    /**
     * 单次生成的 token 用量（模型 usage 累加）——原为 VueProjectAgent 的内部 record，
     * S3-2 前半迁到这里，使"用量"概念与累加逻辑同处一类。
     */
    public record TokenUsage(long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
