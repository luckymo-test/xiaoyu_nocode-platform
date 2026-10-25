package com.mofanyi.ai.reasoning;

/**
 * 一次生成实际生效的推理与预算参数。
 */
public record ReasoningProfile(
        boolean thinkingEnabled,
        String effort,
        int maxSteps,
        long maxLoopTokens,
        int repairRounds,
        int maxOutputTokens,
        String model,
        long maxDurationSeconds) {

    public static final long DEFAULT_MAX_DURATION_SECONDS = 900;

    /**
     * 保持现有调用方兼容；未显式配置时使用保守的 15 分钟上限。
     */
    public ReasoningProfile(boolean thinkingEnabled,
                            String effort,
                            int maxSteps,
                            long maxLoopTokens,
                            int repairRounds,
                            int maxOutputTokens,
                            String model) {
        this(thinkingEnabled, effort, maxSteps, maxLoopTokens, repairRounds,
                maxOutputTokens, model, DEFAULT_MAX_DURATION_SECONDS);
    }

    public ReasoningProfile {
        effort = thinkingEnabled ? effort : null;
        maxDurationSeconds = maxDurationSeconds > 0
                ? maxDurationSeconds : DEFAULT_MAX_DURATION_SECONDS;
    }
}
