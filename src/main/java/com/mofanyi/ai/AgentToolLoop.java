package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfile;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.ai.tools.DiagramTool;
import com.mofanyi.ai.tools.FetchWebTool;
import com.mofanyi.ai.tools.FinishTool;
import com.mofanyi.ai.tools.ReadFileTool;
import com.mofanyi.ai.tools.SearchImageTool;
import com.mofanyi.ai.tools.SearchWebTool;
import com.mofanyi.ai.tools.WriteFileTool;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.AiCallListener;
import com.mofanyi.core.ImageMaterialPlanner;
import com.mofanyi.core.TokenUsageAccumulator;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 生成、修复、修改共用的唯一工具循环实现。
 *
 * <p>循环支持框架内部执行工具与自管执行两种模式；预算、取消、流式聚合、L1 压缩、
 * 推理档位和调用打点都只在这里维护。
 */
@Slf4j
@Component
public class AgentToolLoop {

    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();
    private static final int SLIM_THRESHOLD_CHARS = 300;
    private static final long MAX_LOOP_MILLIS = 600_000L;

    private final ChatModel chatModel;
    private final WriteFileTool writeFileTool;
    private final ReadFileTool readFileTool;
    private final SearchImageTool searchImageTool;
    private final SearchWebTool searchWebTool;
    private final FetchWebTool fetchWebTool;
    private final DiagramTool diagramTool;
    private final FinishTool finishTool;
    private final GenerationTaskRegistry taskRegistry;
    private final AiCallListener aiCallListener;
    private final ReasoningProfileRegistry reasoningProfiles;

    @Value("${codegen.agent.self-managed-loop.enabled:false}")
    private boolean selfManagedLoopEnabled;

    @Value("${codegen.agent.context-slim.enabled:false}")
    private boolean contextSlimEnabled;

    public AgentToolLoop(@Qualifier("openAiChatModel") ChatModel chatModel,
                         WriteFileTool writeFileTool,
                         ReadFileTool readFileTool,
                         SearchImageTool searchImageTool,
                         SearchWebTool searchWebTool,
                         FetchWebTool fetchWebTool,
                         DiagramTool diagramTool,
                         FinishTool finishTool,
                         GenerationTaskRegistry taskRegistry,
                         AiCallListener aiCallListener,
                         ReasoningProfileRegistry reasoningProfiles) {
        this.chatModel = chatModel;
        this.writeFileTool = writeFileTool;
        this.readFileTool = readFileTool;
        this.searchImageTool = searchImageTool;
        this.searchWebTool = searchWebTool;
        this.fetchWebTool = fetchWebTool;
        this.diagramTool = diagramTool;
        this.finishTool = finishTool;
        this.taskRegistry = taskRegistry;
        this.aiCallListener = aiCallListener;
        this.reasoningProfiles = reasoningProfiles;
    }

    /**
     * 一次 Agent 工具循环所需的全部运行时状态。
     */
    public record ToolLoopRequest(
            CodeGenTypeEnum type,
            Long appId,
            List<Message> messages,
            Map<String, String> files,
            AtomicBoolean finished,
            AtomicReference<String> description,
            TokenUsageAccumulator usage,
            AiCallContext ctx,
            AgentProgressListener listener,
            List<ImageMaterialPlanner.Material> materials,
            ReasoningLevel reasoningLevel) {
    }

    /**
     * 执行工具循环。messages/files 由调用方准备，循环会追加历史并收集 writeFile 结果。
     */
    public void run(ToolLoopRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.type() == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.appId() == null || request.appId() <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.messages() == null || request.files() == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.finished() == null || request.description() == null,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.ctx() == null, ErrorCode.PARAMS_ERROR);

        ReasoningLevel level = request.reasoningLevel() == null
                ? reasoningProfiles.defaultReasoningLevel() : request.reasoningLevel();
        ReasoningProfile profile = reasoningProfiles.resolve(level);
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(writeFileTool.build(
                request.type(), request.appId(), request.files(), request.listener()));
        callbacks.add(readFileTool.build(request.type(), request.appId()));
        callbacks.add(searchImageTool.build(
                request.appId(), request.materials() == null ? List.of() : request.materials()));
        if (searchWebTool.isEnabled()) {
            callbacks.add(searchWebTool.build());
        }
        if (fetchWebTool.isEnabled()) {
            callbacks.add(fetchWebTool.build());
        }
        callbacks.add(diagramTool.build(request.appId()));
        callbacks.add(finishTool.build(request.finished(), request.description()));
        List<ToolCallback> toolCallbacks = List.copyOf(callbacks);

        log.info("[agent] reasoning_level={} thinking={} effort={} maxSteps={} maxLoopTokens={} model={}",
                level.code(), profile.thinkingEnabled() ? "on" : "off",
                profile.thinkingEnabled() ? profile.effort() : "off",
                profile.maxSteps(), profile.maxLoopTokens(), profile.model());
        if (selfManagedLoopEnabled) {
            runSelfManaged(request, profile, level, toolCallbacks);
        } else {
            runFramework(request, profile, level, toolCallbacks);
        }
    }

    private void runFramework(ToolLoopRequest request, ReasoningProfile profile, ReasoningLevel level,
                              List<ToolCallback> toolCallbacks) {
        int consecutiveFailures = 0;
        long loopStart = System.currentTimeMillis();
        int stepCount = 0;
        OpenAiChatOptions options = options(profile, toolCallbacks, true);
        for (int step = 0; step < profile.maxSteps(); step++) {
            stepCount++;
            logRoundStart("framework", stepCount, request.usage(), profile.maxLoopTokens(), loopStart);
            long callStart = System.currentTimeMillis();
            long beforeIn = usageValue(request.usage(), true);
            long beforeOut = usageValue(request.usage(), false);
            ChatResponse response;
            try {
                response = streamAndAggregate(new Prompt(request.messages(), options),
                        reasoningSink(request.listener()));
            } catch (RuntimeException e) {
                long ms = System.currentTimeMillis() - callStart;
                aiCallListener.onFailure(request.ctx(), e, ms, level.code());
                log.warn("[agent] 模型响应异常（参数截断/解析失败），step={}，提示模型重试", step, e);
                if (++consecutiveFailures >= 3) {
                    log.error("[agent] 连续 3 次模型响应异常，终止生成，step={}", step);
                    break;
                }
                request.messages().add(new UserMessage(
                        "你上一步的返回内容被截断（工具调用参数不完整，解析失败）。"
                                + "请重新调用工具：若文件内容较大，请拆成多次 writeFile 调用，每次内容控制在 3000 字符以内；"
                                + "若已完成，直接调用 finish。"));
                continue;
            }
            consecutiveFailures = 0;
            taskRegistry.checkCancelled(request.appId());
            checkBudget(loopStart, usageValue(request.usage(), true),
                    usageValue(request.usage(), false), profile.maxLoopTokens());
            accumulateUsage(request.usage(), response);
            logCallResult("framework", stepCount, request.usage(), response, callStart,
                    beforeIn, beforeOut, request.ctx(), level);
            setDescription(request.description(), response);
            break;
        }
        log.info("[agent] tool-loop 结束：外层 step={}，写文件数={}，总耗时={}ms",
                stepCount, request.files().size(), System.currentTimeMillis() - loopStart);
    }

    private void runSelfManaged(ToolLoopRequest request, ReasoningProfile profile, ReasoningLevel level,
                                List<ToolCallback> toolCallbacks) {
        OpenAiChatOptions options = options(profile, toolCallbacks, false);
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : toolCallbacks) {
            byName.put(callback.getToolDefinition().name(), callback);
        }
        long loopStart = System.currentTimeMillis();
        int consecutiveFailures = 0;
        int lastRoundStart = request.messages().size();
        for (int round = 1; round <= profile.maxSteps(); round++) {
            taskRegistry.checkCancelled(request.appId());
            if (contextSlimEnabled) {
                int slimmed = slimHistory(request.messages(), lastRoundStart);
                if (slimmed > 0) {
                    log.info("[agent] round={} 模式=self L1 压缩：{} 处旧历史（工具参数/结果）已换成占位",
                            round, slimmed);
                }
            }
            checkBudget(loopStart, usageValue(request.usage(), true),
                    usageValue(request.usage(), false), profile.maxLoopTokens());
            logRoundStart("self", round, request.usage(), profile.maxLoopTokens(), loopStart);

            long callStart = System.currentTimeMillis();
            long beforeIn = usageValue(request.usage(), true);
            long beforeOut = usageValue(request.usage(), false);
            ChatResponse response;
            try {
                response = streamAndAggregate(new Prompt(request.messages(), options),
                        reasoningSink(request.listener()));
            } catch (RuntimeException e) {
                aiCallListener.onFailure(request.ctx(), e, System.currentTimeMillis() - callStart, level.code());
                if (++consecutiveFailures >= 3) {
                    throw e;
                }
                request.messages().add(new UserMessage("上一轮调用失败，请重试；内容较大时请拆小后再写。"));
                continue;
            }
            consecutiveFailures = 0;
            accumulateUsage(request.usage(), response);
            logCallResult("self", round, request.usage(), response, callStart,
                    beforeIn, beforeOut, request.ctx(), level);

            AssistantMessage output = response.getResult().getOutput();
            lastRoundStart = request.messages().size();
            request.messages().add(output);
            if (StrUtil.isNotBlank(output.getText()) && request.description().get().isBlank()) {
                request.description().set(output.getText());
            }
            if (!output.hasToolCalls()) {
                break;
            }
            log.info("[agent] round={} 模式=self 工具调用={}", round,
                    output.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                ToolCallback callback = byName.get(toolCall.name());
                String result;
                try {
                    result = callback == null
                            ? "未知工具: " + toolCall.name()
                            : callback.call(toolCall.arguments());
                } catch (RuntimeException e) {
                    result = "工具执行失败: " + e.getMessage();
                }
                responses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), result));
            }
            request.messages().add(ToolResponseMessage.builder().responses(responses).build());
            if (request.finished().get()) {
                break;
            }
        }
    }

    private OpenAiChatOptions options(ReasoningProfile profile, List<ToolCallback> toolCallbacks,
                                      boolean internalToolExecution) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(profile.model())
                .maxTokens(profile.maxOutputTokens())
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(internalToolExecution);
        if (profile.thinkingEnabled()) {
            builder.reasoningEffort(profile.effort());
        } else {
            builder.extraBody(Map.of("thinking", Map.of("type", "disabled")));
        }
        return builder.build();
    }

    private void logCallResult(String mode, int round, TokenUsageAccumulator usage,
                               ChatResponse response, long callStart, long beforeIn, long beforeOut,
                               AiCallContext ctx, ReasoningLevel level) {
        long callMs = System.currentTimeMillis() - callStart;
        long inDelta = usageValue(usage, true) - beforeIn;
        long outDelta = usageValue(usage, false) - beforeOut;
        aiCallListener.onSuccess(ctx, finishReasonOf(response), inDelta, outDelta, callMs, level.code());
        log.info("[agent] round={} 模式={} 本轮prompt={} 本轮completion={} total={} 调用耗时={}ms",
                round, mode, inDelta, outDelta, inDelta + outDelta, callMs);
    }

    private static void logRoundStart(String mode, int round, TokenUsageAccumulator usage,
                                      long maxLoopTokens, long loopStart) {
        log.info("[agent] round={} 模式={} prompt={} completion={} 累计token={}/{} 已耗时={}ms",
                round, mode, usageValue(usage, true), usageValue(usage, false),
                usage == null ? 0 : usage.totalTokens(), maxLoopTokens,
                System.currentTimeMillis() - loopStart);
    }

    /**
     * 预算校验：超出墙钟或累计 token 预算即抛业务异常终止。
     */
    public static void checkBudget(long loopStartMillis, long promptTokens,
                                   long completionTokens, long limitTokens) {
        long elapsed = System.currentTimeMillis() - loopStartMillis;
        ThrowUtils.throwIf(elapsed > MAX_LOOP_MILLIS, ErrorCode.OPERATION_ERROR,
                "生成超出时限（已 " + (elapsed / 1000) + "s，上限 " + (MAX_LOOP_MILLIS / 1000) + "s），已终止");
        long total = promptTokens + completionTokens;
        ThrowUtils.throwIf(total > limitTokens, ErrorCode.OPERATION_ERROR,
                "生成超出 token 预算（已 " + total + "，上限 " + limitTokens + "），已终止");
    }

    /**
     * 流式调用并聚合文本、tool calls、reasoning 与 usage。
     */
    ChatResponse streamAndAggregate(Prompt prompt, Consumer<String> reasoningSink) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicReference<ChatResponseMetadata> metaRef = new AtomicReference<>();
        AtomicReference<ChatGenerationMetadata> genMetaRef = new AtomicReference<>();
        AtomicReference<List<AssistantMessage.ToolCall>> toolCallsRef = new AtomicReference<>(List.of());
        long start = System.nanoTime();
        chatModel.stream(prompt)
                .doOnNext(chunk -> {
                    Generation generation = chunk.getResult();
                    if (generation != null) {
                        AssistantMessage output = generation.getOutput();
                        if (output != null) {
                            if (!output.hasToolCalls() && output.getText() != null) {
                                text.append(output.getText());
                            }
                            if (output.getMetadata() != null) {
                                Object value = output.getMetadata().get("reasoningContent");
                                if (value != null) {
                                    String delta = String.valueOf(value);
                                    reasoning.append(delta);
                                    if (reasoningSink != null && !delta.isEmpty()) {
                                        reasoningSink.accept(delta);
                                    }
                                }
                            }
                            if (output.hasToolCalls()) {
                                toolCallsRef.set(output.getToolCalls());
                            }
                            genMetaRef.set(generation.getMetadata());
                        }
                    }
                    if (chunk.getMetadata() != null) {
                        metaRef.set(chunk.getMetadata());
                    }
                })
                .collectList()
                .block();
        log.info("[agent] 单次流式调用完成：reasoning={}字符 text={}字符 耗时={}ms",
                reasoning.length(), text.length(), (System.nanoTime() - start) / 1_000_000);
        AssistantMessage.Builder messageBuilder = AssistantMessage.builder()
                .content(text.toString())
                .toolCalls(toolCallsRef.get());
        if (!reasoning.isEmpty()) {
            messageBuilder.properties(Map.of("reasoningContent", reasoning.toString()));
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(messageBuilder.build(), genMetaRef.get())))
                .metadata(metaRef.get() == null ? ChatResponseMetadata.builder().build() : metaRef.get())
                .build();
    }

    int slimHistory(List<Message> messages, int keepFromIndex) {
        int slimmed = 0;
        int limit = Math.min(keepFromIndex, messages.size());
        for (int i = 0; i < limit; i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage toolResponses) {
                if (slimToolResponses(toolResponses, messages, i)) {
                    slimmed++;
                }
                continue;
            }
            if (!(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) {
                continue;
            }
            List<AssistantMessage.ToolCall> compacted = new ArrayList<>(assistant.getToolCalls().size());
            boolean changed = false;
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                String compactedArguments = compactWriteFileArguments(call);
                if (compactedArguments == null) {
                    compacted.add(call);
                } else {
                    compacted.add(new AssistantMessage.ToolCall(
                            call.id(), call.type(), call.name(), compactedArguments));
                    changed = true;
                    slimmed++;
                }
            }
            if (changed) {
                AssistantMessage.Builder builder = AssistantMessage.builder()
                        .content(assistant.getText())
                        .toolCalls(compacted);
                if (assistant.getMetadata() != null && !assistant.getMetadata().isEmpty()) {
                    builder.properties(assistant.getMetadata());
                }
                messages.set(i, builder.build());
            }
        }
        return slimmed;
    }

    private boolean slimToolResponses(ToolResponseMessage message, List<Message> messages, int index) {
        List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>();
        boolean changed = false;
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            String data = response.responseData();
            if (data != null && data.length() >= SLIM_THRESHOLD_CHARS) {
                compacted.add(new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(),
                        "（已省略 " + data.length() + " 字符：文件内容在工程目录里，需要时重新 readFile）"));
                changed = true;
            } else {
                compacted.add(response);
            }
        }
        if (changed) {
            messages.set(index, ToolResponseMessage.builder().responses(compacted).build());
        }
        return changed;
    }

    private String compactWriteFileArguments(AssistantMessage.ToolCall call) {
        if (!"writeFile".equals(call.name()) || call.arguments() == null) {
            return null;
        }
        try {
            JsonNode arguments = COMPACT_MAPPER.readTree(call.arguments());
            String content = arguments.path("content").asText("");
            if (content.length() < SLIM_THRESHOLD_CHARS) {
                return null;
            }
            ObjectNode compacted = COMPACT_MAPPER.createObjectNode();
            compacted.put("path", arguments.path("path").asText(""));
            compacted.put("content",
                    "（已省略 " + content.length() + " 字符：内容已写入磁盘，需要时用 readFile 取回）");
            return COMPACT_MAPPER.writeValueAsString(compacted);
        } catch (Exception e) {
            return null;
        }
    }

    private static String finishReasonOf(ChatResponse response) {
        try {
            if (response == null || response.getResult() == null
                    || response.getResult().getMetadata() == null) {
                return null;
            }
            return response.getResult().getMetadata().getFinishReason();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void setDescription(AtomicReference<String> description, ChatResponse response) {
        if (!description.get().isBlank() || response == null || response.getResult() == null) {
            return;
        }
        String text = response.getResult().getOutput().getText();
        if (text != null) {
            description.set(text);
        }
    }

    private static Consumer<String> reasoningSink(AgentProgressListener listener) {
        return listener == null ? null : listener::onReasoning;
    }

    private static void accumulateUsage(TokenUsageAccumulator usage, ChatResponse response) {
        if (usage != null) {
            usage.accumulate(response);
        }
    }

    private static long usageValue(TokenUsageAccumulator usage, boolean prompt) {
        if (usage == null) {
            return 0;
        }
        return prompt ? usage.promptTokens() : usage.completionTokens();
    }
}
