package com.mofanyi.ai.model;

import com.mofanyi.core.AiCallContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

public record ModelRequest(
        ModelTask task,
        List<Message> messages,
        List<ToolCallback> tools,
        Set<ModelCapability> requiredCapabilities,
        double temperature,
        int requestedOutputTokens,
        boolean thinkingEnabled,
        String reasoningEffort,
        boolean internalToolExecution,
        String preferredModelId,
        AiCallContext callContext) {

    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        requiredCapabilities = requiredCapabilities == null
                ? Set.of() : Set.copyOf(requiredCapabilities);
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        requestedOutputTokens = Math.max(1, requestedOutputTokens);
    }

    public int estimatedInputTokens() {
        int chars = 0;
        for (Message message : messages) {
            String text = message == null ? null : message.getText();
            if (text != null) {
                chars += text.length();
            }
        }
        return Math.max(1, chars / 3);
    }

    public static ModelRequest textTask(ModelTask task,
                                        List<Message> messages,
                                        double temperature,
                                        int requestedOutputTokens) {
        return new ModelRequest(
                task, messages, List.of(), Set.of(), temperature,
                requestedOutputTokens, false, null, false, null, null);
    }
}
