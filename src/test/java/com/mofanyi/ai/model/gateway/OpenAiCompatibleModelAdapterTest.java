package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelCapability;
import com.mofanyi.ai.model.ModelDescriptor;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleModelAdapterTest {

    @Test
    @DisplayName("适配器隔离 OpenAI/DeepSeek 参数并映射统一响应")
    void mapsProviderResponseAndOptions() {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage output = AssistantMessage.builder()
                .content("hello")
                .properties(java.util.Map.of("reasoningContent", "reasoning"))
                .build();
        Generation generation = new Generation(output,
                ChatGenerationMetadata.builder().finishReason("stop").build());
        ChatResponse response = new ChatResponse(List.of(generation),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(12, 5))
                        .build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        OpenAiCompatibleModelAdapter adapter =
                new OpenAiCompatibleModelAdapter(chatModel);
        ModelDescriptor descriptor = new ModelDescriptor(
                "deepseek-flash", "deepseek", "deepseek-chat",
                128_000, 32_768,
                Set.of(ModelCapability.REASONING),
                "deepseek-thinking", true);
        ModelRequest request = new ModelRequest(
                ModelTask.GENERATE,
                List.of(new UserMessage("hello")),
                List.of(),
                Set.of(),
                0.2,
                4_096,
                true,
                "high",
                false,
                null,
                null);

        ModelResponse result = adapter.chat(descriptor, request);

        assertEquals("hello", result.text());
        assertEquals("reasoning", result.reasoning());
        assertEquals("stop", result.finishReason());
        assertEquals(12, result.usage().promptTokens());
        assertEquals(5, result.usage().completionTokens());
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions options =
                (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals("deepseek-chat", options.getModel());
        assertEquals(4_096, options.getMaxTokens());
        assertEquals("high", options.getReasoningEffort());
    }

    @Test
    @DisplayName("关闭 thinking 时 DeepSeek 参数只在适配器内生成")
    void disablesDeepSeekThinkingInsideAdapter() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build()));
        OpenAiCompatibleModelAdapter adapter =
                new OpenAiCompatibleModelAdapter(chatModel);
        ModelDescriptor descriptor = new ModelDescriptor(
                "deepseek-flash", "deepseek", "deepseek-chat",
                128_000, 32_768, Set.of(), "deepseek-thinking", true);
        ModelRequest request = new ModelRequest(
                ModelTask.COMPRESS, List.of(new UserMessage("summarize")),
                List.of(), Set.of(), 0, 1_000, false, null,
                false, null, null);

        adapter.chat(descriptor, request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        OpenAiChatOptions options =
                (OpenAiChatOptions) promptCaptor.getValue().getOptions();
        assertEquals(java.util.Map.of("thinking", java.util.Map.of("type", "disabled")),
                options.getExtraBody());
    }
}
