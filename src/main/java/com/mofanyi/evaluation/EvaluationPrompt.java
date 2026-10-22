package com.mofanyi.evaluation;

import java.util.List;

public record EvaluationPrompt(
        String id,
        String category,
        String prompt,
        List<String> requiredCapabilities,
        String viewportProfile,
        int weight) {

    public EvaluationPrompt {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}
