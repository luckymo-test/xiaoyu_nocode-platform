package com.mofanyi.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 探针：当前模型/网关是否真的支持 reasoning_effort，以及不同档位的差异。
 *
 * <p>同一道多步推理题，分别用 thinking off / low / high / max 发一次，比较
 * reasoning 字符数、completion token、耗时、最终答案。
 * 如果三档几乎一样 → 网关忽略该字段（滑杆得靠别的杠杆）。
 *
 * <p>默认不跑：
 * <pre>mvn -o test -Dtest=ReasoningEffortProbeTest -DcodegenProbe=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ReasoningEffortProbeTest {

    private static final String QUESTION = """
            一个仓库有 A、B 两个货架。A 上的箱子数是 B 的 3 倍。
            从 A 搬 40 个箱子到 B 之后，A 比 B 少 10 个。
            问最初 A、B 各有多少个箱子？
            请先推理，最后一行只写：A=?, B=?""";

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Test
    @DisplayName("探针：thinking off / low / high / max 差异")
    void probeReasoningEffort() {
        for (ProbeCase probeCase : List.of(
                new ProbeCase("fast-off", null, false),
                new ProbeCase("eco-low", "low", true),
                new ProbeCase("standard-high", "high", true),
                new ProbeCase("max", "max", true))) {
            probeOne(probeCase);
        }
    }

    private record ProbeCase(String label, String effort, boolean thinkingEnabled) {
    }

    private void probeOne(ProbeCase probeCase) {
        AtomicLong reasoningChars = new AtomicLong();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        AtomicReference<String> text = new AtomicReference<>("");
        StringBuilder sb = new StringBuilder();
        long start = System.currentTimeMillis();
        try {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
            if (probeCase.thinkingEnabled()) {
                builder.reasoningEffort(probeCase.effort());
            } else {
                builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            }
            var options = builder.build();
            chatModel.stream(new Prompt(List.of(new UserMessage(QUESTION)), options))
                    .doOnNext(chunk -> {
                        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                            return;
                        }
                        var out = chunk.getResult().getOutput();
                        if (out.getMetadata() != null && out.getMetadata().get("reasoningContent") != null) {
                            reasoningChars.addAndGet(
                                    String.valueOf(out.getMetadata().get("reasoningContent")).length());
                        }
                        if (!out.hasToolCalls() && out.getText() != null) {
                            sb.append(out.getText());
                        }
                        if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                            var usage = chunk.getMetadata().getUsage();
                            if (usage.getPromptTokens() != null) {
                                promptTokens.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokens.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .collectList()
                    .block();
            text.set(sb.toString());
            System.out.println("[effort-probe] level=" + probeCase.label()
                    + " effort=" + (probeCase.thinkingEnabled() ? probeCase.effort() : "off")
                    + " reasoningChars=" + reasoningChars
                    + " promptTokens=" + promptTokens
                    + " completionTokens=" + completionTokens
                    + " ms=" + (System.currentTimeMillis() - start)
                    + " answer=" + text.get().replace('\n', ' '));
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("[effort-probe] level=" + probeCase.label() + " FAILED: "
                    + e.getClass().getSimpleName() + " | root=" + root.getClass().getSimpleName()
                    + ": " + root.getMessage());
        }
    }
}
