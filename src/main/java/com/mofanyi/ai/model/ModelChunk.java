package com.mofanyi.ai.model;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public record ModelChunk(
        String text,
        String reasoning,
        List<AssistantMessage.ToolCall> toolCalls,
        String finishReason,
        ModelUsage usage,
        String provider,
        String modelId,
        String modelName) {

    public ModelChunk {
        text = text == null ? "" : text;
        reasoning = reasoning == null ? "" : reasoning;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
