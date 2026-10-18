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
        String model) {

    public ReasoningProfile {
        effort = thinkingEnabled ? effort : null;
    }
}
