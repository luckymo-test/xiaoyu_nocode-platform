package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelCapability;
import com.mofanyi.ai.model.ModelChunk;
import com.mofanyi.ai.model.ModelDescriptor;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpenAiCompatibleModelAdapter implements ModelAdapter {

    private static final Set<String> SUPPORTED_PROVIDERS =
            Set.of("deepseek", "openai-compatible", "openai");

    private final ChatModel chatModel;

    public OpenAiCompatibleModelAdapter(
            @Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && SUPPORTED_PROVIDERS.contains(provider);
    }

    @Override
    public ModelResponse chat(ModelDescriptor descriptor, ModelRequest request) {
        ChatResponse response = chatModel.call(new Prompt(
                request.messages(), options(descriptor, request)));
        return toResponse(descriptor, response);
    }

    @Override
    public Flux<ModelChunk> stream(ModelDescriptor descriptor, ModelRequest request) {
        return chatModel.stream(new Prompt(request.messages(), options(descriptor, request)))
                .map(response -> toChunk(descriptor, response));
    }

    private OpenAiChatOptions options(ModelDescriptor descriptor, ModelRequest request) {
        int maxTokens = Math.min(request.requestedOutputTokens(),
                descriptor.maxOutputTokens());
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(descriptor.modelName())
                .temperature(request.temperature())
                .maxTokens(maxTokens)
                .internalToolExecutionEnabled(request.internalToolExecution());
        if (!request.tools().isEmpty()) {
            builder.toolCallbacks(request.tools());
        }
        if (request.thinkingEnabled()) {
            if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
                builder.reasoningEffort(request.reasoningEffort());
            }
        } else if ("deepseek".equalsIgnoreCase(descriptor.provider())) {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private ModelResponse toResponse(ModelDescriptor descriptor, ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        String text = output == null ? "" : output.getText();
        String reasoning = output == null ? "" : metadataString(output, "reasoningContent");
        List<AssistantMessage.ToolCall> toolCalls =
                output == null ? List.of() : output.getToolCalls();
        String finishReason = generation == null || generation.getMetadata() == null
                ? null : generation.getMetadata().getFinishReason();
        return new ModelResponse(text, reasoning, toolCalls, finishReason,
                usage(response), descriptor.provider(), descriptor.id(), descriptor.modelName());
    }

    private ModelChunk toChunk(ModelDescriptor descriptor, ChatResponse response) {
        Generation generation = response == null ? null : response.getResult();
        AssistantMessage output = generation == null ? null : generation.getOutput();
        String text = output == null ? "" : output.getText();
        String reasoning = output == null ? "" : metadataString(output, "reasoningContent");
        List<AssistantMessage.ToolCall> toolCalls =
                output == null ? List.of() : output.getToolCalls();
        String finishReason = generation == null || generation.getMetadata() == null
                ? null : generation.getMetadata().getFinishReason();
        return new ModelChunk(text, reasoning, toolCalls, finishReason,
                usage(response), descriptor.provider(), descriptor.id(), descriptor.modelName());
    }

    private static ModelUsage usage(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage == null) {
            return new ModelUsage(0, 0);
        }
        return new ModelUsage(
                usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }

    private static String metadataString(AssistantMessage output, String key) {
        if (output.getMetadata() == null) {
            return "";
        }
        Object value = output.getMetadata().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
