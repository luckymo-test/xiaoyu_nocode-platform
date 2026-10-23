package com.mofanyi.ai.model;

public record ModelUsage(int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
