package com.mofanyi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 探针（plan-refactor 7.1「方案 a 的前提」）：自管循环下流式调用时，
 * <b>tool-call 片段到底能不能在 {@code doOnNext} 里被观测到</b>。
 *
 * <p>为什么要探：自管循环当前用非流式 {@code call()}（方案 b），代价是 {@code reasoning} 透传退化（验收 ④）。
 * 方案 a 的前提是"流式 + 关闭框架内部工具执行"时，我们自己能在流里看见 tool_calls 增量并重组。
 * <b>若前提不成立，就不该写那个累加器</b>——写了也拿不到片段。
 *
 * <p>本探针会真实调用模型（两次：关闭/开启内部工具执行各一次，用于对照），
 * 因此默认不参与常规测试；显式开启：
 * <pre>mvn -o test -Dtest=SelfManagedStreamProbeTest -DcodegenProbe=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class SelfManagedStreamProbeTest {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record NoteRequest(String title, String body) {
    }

    private ToolCallback writeNoteTool() {
        return FunctionToolCallback.builder("writeNote", (NoteRequest req) ->
                        "已记录：" + (req == null ? "null" : req.title()))
                .description("写一条笔记。用户要求记录时必须调用本工具，不要用文字代替。")
                .inputType(NoteRequest.class)
                .build();
    }

    /** 长载荷探针的目标长度：真实 writeFile 参数常有数千字符，小载荷探不出"片段化" */
    private static final int[] LONG_PAYLOAD_SIZES = {1500, 6000};

    private static String longBody(int chars) {
        return "甲乙丙丁戊己庚辛壬癸".repeat(chars / 10 + 1).substring(0, chars);
    }

    private static List<Message> probeMessages(int longPayloadChars) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(
                "你只有一个工具 writeNote。用户要求记录时你必须调用它，禁止用普通文字回复代替工具调用。"));
        messages.add(new UserMessage(longPayloadChars > 0
                ? "请调用 writeNote 记录一条笔记：title 为「长载荷探针」，body 必须**逐字**填写下面这段文字（"
                + longPayloadChars + " 个字，必须完整，不得省略、不得用省略号代替）：\n" + longBody(longPayloadChars)
                : "请调用 writeNote 记录一条笔记：title 为「探针」，body 为「验证流式 tool-call 片段是否可见」。"));
        return messages;
    }

    @Test
    @DisplayName("探针：关闭框架内部工具执行时，流式 tool-call 片段是否可见")
    void probeToolCallChunks() {
        List<ProbeReport> offReports = new ArrayList<>();
        offReports.add(runProbe(false, 0));
        for (int size : LONG_PAYLOAD_SIZES) {
            offReports.add(runProbe(false, size));
        }
        ProbeReport onSmall = runProbe(true, 0);

        System.out.println();
        System.out.println("================ 探针结论 ================");
        for (ProbeReport r : offReports) {
            System.out.println("内部执行 OFF: " + r.verdict());
        }
        System.out.println("内部执行 ON  / 短载荷: " + onSmall.verdict());
        System.out.println("=========================================");
        for (ProbeReport r : offReports) {
            System.out.println("[probe] 逐 chunk 明细（OFF/" + r.payloadLabel() + "）：\n" + r.detail());
        }
        System.out.println("[probe] 逐 chunk 明细（ON/短）：\n" + onSmall.detail());
    }

    private ProbeReport runProbe(boolean internalToolExecution, int longPayloadChars) {
        var options = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(List.of(writeNoteTool()))
                .internalToolExecutionEnabled(internalToolExecution)
                .build();

        ProbeReport report = new ProbeReport(internalToolExecution, longPayloadChars);
        AtomicInteger index = new AtomicInteger();
        try {
            chatModel.stream(new Prompt(probeMessages(longPayloadChars), options))
                    .doOnNext(chunk -> report.record(index.getAndIncrement(), chunk))
                    .collectList()
                    .block();
        } catch (RuntimeException e) {
            report.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return report;
    }

    /**
     * 收集逐 chunk 的可观测事实：tool_calls 是否出现、id/name 是否只在首片、arguments 是否被切开、能否拼回合法 JSON
     */
    private static final class ProbeReport {

        private final boolean internalToolExecution;
        private final int payloadChars;
        private final StringBuilder detail = new StringBuilder();
        private int chunks;
        private int chunksWithToolCalls;
        private int fragmentsWithId;
        private int fragmentsWithName;
        private int fragmentsWithArguments;
        private int fragmentsWithReasoning;
        private int chunksWithNonZeroUsage;
        private long argFragmentChars;
        private final StringBuilder concatenatedArguments = new StringBuilder();
        private String failure;

        ProbeReport(boolean internalToolExecution, int payloadChars) {
            this.internalToolExecution = internalToolExecution;
            this.payloadChars = payloadChars;
        }

        void record(int i, org.springframework.ai.chat.model.ChatResponse chunk) {
            chunks++;
            if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                return;
            }
            var out = chunk.getResult().getOutput();
            boolean hasTools = out.hasToolCalls();
            if (hasTools) {
                chunksWithToolCalls++;
            }

            String reasoning = null;
            if (out.getMetadata() != null && out.getMetadata().get("reasoningContent") != null) {
                reasoning = String.valueOf(out.getMetadata().get("reasoningContent"));
                fragmentsWithReasoning++;
            }
            var usage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
            boolean nonZeroUsage = usage != null && usage.getPromptTokens() != null
                    && (usage.getPromptTokens() > 0
                    || (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0));
            if (nonZeroUsage) {
                chunksWithNonZeroUsage++;
            }

            StringBuilder line = new StringBuilder();
            line.append("  chunk#").append(i)
                    .append(" text=").append(out.getText() == null ? 0 : out.getText().length())
                    .append(" toolCalls=").append(hasTools ? out.getToolCalls().size() : 0)
                    .append(" reasoning=").append(reasoning == null ? "-" : reasoning.length())
                    .append(" usage=").append(usage != null && usage.getPromptTokens() != null
                            ? (usage.getPromptTokens() + "/" + usage.getCompletionTokens()) : "-");

            if (hasTools) {
                for (var tc : out.getToolCalls()) {
                    String id = tc.id();
                    String name = tc.name();
                    String args = tc.arguments();
                    if (id != null && !id.isBlank()) {
                        fragmentsWithId++;
                    }
                    if (name != null && !name.isBlank()) {
                        fragmentsWithName++;
                    }
                    if (args != null && !args.isEmpty()) {
                        fragmentsWithArguments++;
                        argFragmentChars += args.length();
                        concatenatedArguments.append(args);
                    }
                    line.append("\n      tool id=").append(id)
                            .append(" name=").append(name)
                            .append(" args(").append(args == null ? 0 : args.length()).append(")=")
                            .append(shorten(args));
                }
            }
            detail.append(line).append('\n');
        }

        private static String shorten(String s) {
            if (s == null) {
                return "null";
            }
            String one = s.replace("\n", "\\n");
            return one.length() <= 90 ? one : one.substring(0, 90) + "…";
        }

        boolean argumentsRebuildable() {
            if (concatenatedArguments.length() == 0) {
                return false;
            }
            try {
                MAPPER.readTree(concatenatedArguments.toString());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        String verdict() {
            if (failure != null) {
                return "调用失败：" + failure;
            }
            return "chunks=" + chunks
                    + " " + payloadLabel()
                    + " 含toolCalls的chunk=" + chunksWithToolCalls
                    + " 片段(有id/有name/有args)=" + fragmentsWithId + "/" + fragmentsWithName + "/"
                    + fragmentsWithArguments + " args字符=" + argFragmentChars
                    + " 拼回合法JSON=" + argumentsRebuildable()
                    + " 带reasoning片段=" + fragmentsWithReasoning
                    + " 带非零usage的chunk=" + chunksWithNonZeroUsage
                    + " => " + (chunksWithToolCalls > 0
                    ? "**tool-call 片段对外可见**" : "tool-call 片段不可见（被框架吞掉）");
        }

        String payloadLabel() {
            return payloadChars > 0 ? ("长载荷" + payloadChars + "字") : "短载荷";
        }

        String detail() {
            return detail.length() == 0 ? "  （无 chunk）" : detail.toString();
        }

        @Override
        public String toString() {
            return "internalToolExecution=" + internalToolExecution + " " + verdict();
        }
    }
}
