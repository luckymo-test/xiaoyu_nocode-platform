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

    public ReasoningProfile {
        effort = thinkingEnabled ? effort : null;
        maxDurationSeconds = maxDurationSeconds > 0
                ? maxDurationSeconds : DEFAULT_MAX_DURATION_SECONDS;
    }

    /**
     * JavaBean accessors keep IDE configuration-property inference aligned with
     * Spring's relaxed kebab-case binding for nested values in a Map.
     */
    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public String getEffort() {
        return effort;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public long getMaxLoopTokens() {
        return maxLoopTokens;
    }

    public int getRepairRounds() {
        return repairRounds;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public String getModel() {
        return model;
    }

    public long getMaxDurationSeconds() {
        return maxDurationSeconds;
    }
}
