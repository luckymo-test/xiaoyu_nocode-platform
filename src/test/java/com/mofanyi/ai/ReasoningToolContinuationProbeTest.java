package com.mofanyi.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 探针：thinking off / high 下连续两轮工具调用是否可用，以及 assistant reasoning_content 随历史回传是否报 400。
 * 默认跳过：{@code mvn -o test -Dtest=ReasoningToolContinuationProbeTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ReasoningToolContinuationProbeTest {

    public record NoteRequest(String title) {
    }

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Test
    @DisplayName("探针：thinking off/high 连续两轮工具调用")
    void probeTwoToolRounds() {
        probe("fast-off", null, false);
        probe("standard-high", "high", true);
    }

    private void probe(String label, String effort, boolean thinkingEnabled) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("你只能通过 writeNote 工具记录笔记，不要用普通文字代替工具调用。"));
        messages.add(new UserMessage("请调用 writeNote 记录：第一轮探针。"));
        try {
            RoundResult first = call(messages, effort, thinkingEnabled);
            if (first.toolCalls().isEmpty()) {
                throw new IllegalStateException("第一轮未返回 tool call");
            }
            AssistantMessage.Builder assistant = AssistantMessage.builder()
                    .content(first.text())
                    .toolCalls(first.toolCalls());
            if (!first.reasoning().isBlank()) {
                assistant.properties(Map.of("reasoningContent", first.reasoning()));
            }
            messages.add(assistant.build());
            List<ToolResponseMessage.ToolResponse> responses = first.toolCalls().stream()
                    .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(), "已记录"))
                    .toList();
            messages.add(ToolResponseMessage.builder().responses(responses).build());
            messages.add(new UserMessage("请再次调用 writeNote 记录：第二轮探针。"));

            RoundResult second = call(messages, effort, thinkingEnabled);
            System.out.println("[reasoning-tool-probe] level=" + label
                    + " firstTools=" + first.toolCalls().size()
                    + " secondTools=" + second.toolCalls().size()
                    + " reasoningChars=" + (first.reasoning().length() + second.reasoning().length())
                    + " => PASS");
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("[reasoning-tool-probe] level=" + label + " FAILED: " + root);
            throw e;
        }
    }

    private RoundResult call(List<Message> messages, String effort, boolean thinkingEnabled) {
        ToolCallback tool = FunctionToolCallback.builder("writeNote", (NoteRequest request) -> "已记录")
                .description("记录一条笔记")
                .inputType(NoteRequest.class)
                .build();
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model("deepseek-flash")
                .toolCallbacks(List.of(tool))
                .internalToolExecutionEnabled(false);
        if (thinkingEnabled) {
            builder.reasoningEffort(effort);
        } else {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicReference<List<AssistantMessage.ToolCall>> toolCalls = new AtomicReference<>(List.of());
        List<ChatResponse> chunks = chatModel.stream(new Prompt(messages, builder.build()))
                .collectList().block();
        if (chunks != null) {
            for (ChatResponse chunk : chunks) {
                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                    continue;
                }
                AssistantMessage output = chunk.getResult().getOutput();
                if (!output.hasToolCalls() && output.getText() != null) {
                    text.append(output.getText());
                }
                if (output.hasToolCalls()) {
                    toolCalls.set(output.getToolCalls());
                }
                if (output.getMetadata() != null && output.getMetadata().get("reasoningContent") != null) {
                    reasoning.append(output.getMetadata().get("reasoningContent"));
                }
            }
        }
        return new RoundResult(text.toString(), reasoning.toString(), toolCalls.get());
    }

    private record RoundResult(String text, String reasoning, List<AssistantMessage.ToolCall> toolCalls) {
    }
}
