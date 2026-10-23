package com.mofanyi.ai.model;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public record ModelResponse(
        String text,
        String reasoning,
        List<AssistantMessage.ToolCall> toolCalls,
        String finishReason,
        ModelUsage usage,
        String provider,
        String modelId,
        String modelName) {

    public ModelResponse {
        text = text == null ? "" : text;
        reasoning = reasoning == null ? "" : reasoning;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? new ModelUsage(0, 0) : usage;
    }
}
