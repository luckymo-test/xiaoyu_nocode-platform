package com.mofanyi.ai;

import com.mofanyi.ai.model.ModelChunk;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.ai.model.ModelProperties;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelUsage;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfile;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.ai.tools.DiagramTool;
import com.mofanyi.ai.tools.EditFileTool;
import com.mofanyi.ai.tools.FetchWebTool;
import com.mofanyi.ai.tools.FinishTool;
import com.mofanyi.ai.tools.ReadFileTool;
import com.mofanyi.ai.tools.SearchImageTool;
import com.mofanyi.ai.tools.SearchWebTool;
import com.mofanyi.ai.tools.WriteFileTool;
import com.mofanyi.ai.tools.WriteFileChunkTool;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.AiCallListener;
import com.mofanyi.core.ImageMaterialPlanner;
import com.mofanyi.core.TokenUsageAccumulator;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.skill.SkillContext;
import com.mofanyi.skill.SkillDefinition;
import com.mofanyi.workflow.AgentToolCallRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolLoopGatewayTest {

    private ModelGateway modelGateway;
    private ModelProperties modelProperties;
    private ReasoningProfileRegistry profiles;
    private AiCallListener callListener;
    private FinishTool finishTool;
    private ToolCallback finishCallback;
    private WriteFileTool writeFileTool;
    private ToolCallback writeFileCallback;
    private SkillContext skillContext;
    private AgentToolLoop loop;

    @BeforeEach
    void setUp() {
        modelGateway = mock(ModelGateway.class);
        modelProperties = new ModelProperties();
        profiles = mock(ReasoningProfileRegistry.class);
        callListener = mock(AiCallListener.class);
        finishTool = mock(FinishTool.class);
        writeFileTool = mock(WriteFileTool.class);
        skillContext = mock(SkillContext.class);
        finishCallback = callback("finish");
        writeFileCallback = callback("writeFile");
        when(skillContext.get(7L)).thenReturn(List.of());
        when(skillContext.allowedToolIntersection(7L)).thenReturn(java.util.Optional.empty());

        when(profiles.resolve(ReasoningLevel.STANDARD)).thenReturn(
                new ReasoningProfile(true, "high", 4, 20_000, 1,
                        4_096, "deepseek-flash"));
        when(finishTool.build(any(), any())).thenReturn(finishCallback);
        when(writeFileTool.build(any(), any(), any(), any())).thenReturn(writeFileCallback);
        SearchWebTool searchWeb = mock(SearchWebTool.class);
        FetchWebTool fetchWeb = mock(FetchWebTool.class);
        when(searchWeb.isEnabled()).thenReturn(false);
        when(fetchWeb.isEnabled()).thenReturn(false);

        loop = new AgentToolLoop(
                modelGateway,
                modelProperties,
                new ModelExecutionTrace(),
                writeFileTool,
                toolWithCallback(mock(WriteFileChunkTool.class), "writeFileChunk"),
                toolWithCallback(mock(EditFileTool.class), "editFile"),
                toolWithCallback(mock(ReadFileTool.class), "readFile"),
                toolWithCallback(mock(SearchImageTool.class), "searchImage"),
                searchWeb,
                fetchWeb,
                toolWithCallback(mock(DiagramTool.class), "diagram"),
                finishTool,
                mock(GenerationTaskRegistry.class),
                callListener,
                profiles,
                skillContext,
                mock(AgentToolCallRecorder.class));
    }

    @Test
    @DisplayName("Gateway 开启时框架工具模式通过统一网关聚合文本、reasoning 和用量")
    void runsFrameworkModeThroughGateway() {
        when(modelGateway.stream(any())).thenReturn(Flux.just(
                new ModelChunk("done", "think", List.of(), "stop",
                        new ModelUsage(10, 2), "deepseek",
                        "deepseek-flash", "deepseek-flash")));
        AtomicReference<String> description = new AtomicReference<>("");
        AtomicBoolean finished = new AtomicBoolean();
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        AgentProgressListener progress = mock(AgentProgressListener.class);
        AgentToolLoop.ToolLoopRequest request = request(
                description, finished, usage, progress);

        loop.run(request);

        assertEquals("done", description.get());
        assertEquals(10, usage.promptTokens());
        assertEquals(2, usage.completionTokens());
        verify(progress).onReasoning("think");
        verify(callListener).onSuccess(any(), eq("stop"), eq(10L), eq(2L), anyLong(),
                eq("standard"), eq("deepseek"), eq("deepseek-flash"),
                eq("deepseek-flash"), eq("GENERATE"));
        verify(modelGateway).stream(any());
    }

    @Test
    @DisplayName("Gateway 自管循环仍会执行模型返回的工具调用")
    void executesToolsInSelfManagedGatewayMode() {
        ReflectionTestUtils.setField(loop, "selfManagedLoopEnabled", true);
        AssistantMessage.ToolCall finishCall = new AssistantMessage.ToolCall(
                "call-1", "function", "finish",
                "{\"description\":\"completed\"}");
        when(modelGateway.stream(any())).thenReturn(Flux.just(
                new ModelChunk("", "", List.of(finishCall), "tool_calls",
                        new ModelUsage(8, 3), "deepseek",
                        "deepseek-flash", "deepseek-flash")));
        AtomicReference<String> description = new AtomicReference<>("");
        AtomicBoolean finished = new AtomicBoolean();
        when(finishCallback.call(anyString())).thenAnswer(invocation -> {
            description.set("completed");
            finished.set(true);
            return "finished";
        });
        AgentToolLoop.ToolLoopRequest request = request(
                description, finished, new TokenUsageAccumulator(), null);

        loop.run(request);

        assertTrue(finished.get());
        assertEquals("completed", description.get());
        assertEquals(3, request.messages().size());
        verify(finishCallback).call(anyString());
    }

    @Test
    @DisplayName("任务路由开启时不把推理档位中的旧模型名当作强制覆盖")
    void taskRoutingDoesNotUseLegacyPreferredModel() {
        modelProperties.getRouting().setEnabled(true);
        when(modelGateway.stream(any())).thenReturn(Flux.just(
                new ModelChunk("done", "", List.of(), "stop",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));

        loop.run(request(new AtomicReference<>(""), new AtomicBoolean(),
                new TokenUsageAccumulator(), null));

        ArgumentCaptor<ModelRequest> requestCaptor =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelGateway).stream(requestCaptor.capture());
        assertNull(requestCaptor.getValue().preferredModelId());
    }

    @Test
    @DisplayName("HTTP/2 流重置和静默超时属于可重试传输错误")
    void classifiesTransientModelTransportFailures() {
        assertTrue(AgentToolLoop.isTransientTransportFailure(new RuntimeException(
                "response failed", new java.io.IOException("Received RST_STREAM: Internal error"))));
        assertTrue(AgentToolLoop.isTransientTransportFailure(
                new java.util.concurrent.TimeoutException("Did not observe any item within 240s")));
        assertFalse(AgentToolLoop.isTransientTransportFailure(
                new IllegalArgumentException("JSON 参数不完整")));
    }

    @Test
    @DisplayName("激活 Skill 后只向模型注册技能允许的工具并保留 finish")
    void filtersToolsByActiveSkills() {
        when(skillContext.allowedToolIntersection(7L))
                .thenReturn(java.util.Optional.of(Set.of("readFile", "writeFile")));
        when(skillContext.get(7L)).thenReturn(List.of(new SkillDefinition(
                "seo-audit", "1", "SEO",
                Set.of("html-static"), Set.of("readFile", "writeFile"),
                Set.of("content-read"), "prompt", List.of(), Set.of())));
        when(modelGateway.stream(any())).thenReturn(Flux.just(
                new ModelChunk("done", "", List.of(), "stop",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));

        loop.run(request(new AtomicReference<>(""), new AtomicBoolean(),
                new TokenUsageAccumulator(), null));

        ArgumentCaptor<ModelRequest> requestCaptor =
                ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelGateway).stream(requestCaptor.capture());
        assertEquals(List.of("editFile", "writeFile", "writeFileChunk", "readFile", "finish"),
                requestCaptor.getValue().tools().stream()
                        .map(callback -> callback.getToolDefinition().name()).toList());
    }

    @Test
    @DisplayName("自管循环拒绝模型伪造的未授权工具名")
    void rejectsForgedToolNameInSelfManagedMode() {
        ReflectionTestUtils.setField(loop, "selfManagedLoopEnabled", true);
        when(skillContext.allowedToolIntersection(7L))
                .thenReturn(java.util.Optional.of(Set.of("readFile", "writeFile")));
        when(skillContext.get(7L)).thenReturn(List.of(new SkillDefinition(
                "seo-audit", "1", "SEO",
                Set.of("html-static"), Set.of("readFile", "writeFile"),
                Set.of("content-read"), "prompt", List.of(), Set.of())));
        AssistantMessage.ToolCall forged = new AssistantMessage.ToolCall(
                "call-forged", "function", "searchImage", "{}");
        AssistantMessage.ToolCall finish = new AssistantMessage.ToolCall(
                "call-finish", "function", "finish", "{\"description\":\"done\"}");
        when(modelGateway.stream(any())).thenReturn(
                Flux.just(new ModelChunk("", "", List.of(forged), "tool_calls",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")),
                Flux.just(new ModelChunk("", "", List.of(finish), "tool_calls",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));
        AtomicBoolean finished = new AtomicBoolean();
        when(finishCallback.call(anyString())).thenAnswer(invocation -> {
            finished.set(true);
            return "finished";
        });

        AgentToolLoop.ToolLoopRequest request = request(
                new AtomicReference<>(""), finished,
                new TokenUsageAccumulator(), null);
        loop.run(request);

        String refusal = request.messages().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(response -> "call-forged".equals(response.id()))
                .findFirst().orElseThrow().responseData();
        assertTrue(refusal.contains("\"code\":\"TOOL_NOT_ALLOWED\""));
        assertTrue(refusal.contains("\"tool\":\"searchImage\""));
    }

    @Test
    @DisplayName("工具参数截断时返回可执行的分块写入提示")
    void returnsChunkedWriteHintForTruncatedArguments() {
        ReflectionTestUtils.setField(loop, "selfManagedLoopEnabled", true);
        when(modelGateway.stream(any())).thenReturn(
                Flux.just(new ModelChunk("", "", List.of(new AssistantMessage.ToolCall(
                        "call-write", "function", "writeFile", "{\"path\":\"index.html\"")),
                        "tool_calls", new ModelUsage(10, 20), "deepseek",
                        "deepseek-flash", "deepseek-flash")),
                Flux.just(new ModelChunk("", "", List.of(new AssistantMessage.ToolCall(
                        "call-finish", "function", "finish", "{\"description\":\"done\"}")),
                        "tool_calls", new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));
        when(writeFileCallback.call(anyString())).thenThrow(new IllegalStateException(
                "Conversion from JSON failed: Unexpected end-of-input"));
        AtomicBoolean finished = new AtomicBoolean();
        when(finishCallback.call(anyString())).thenAnswer(invocation -> {
            finished.set(true);
            return "finished";
        });
        AgentToolLoop.ToolLoopRequest request = request(
                new AtomicReference<>(""), finished,
                new TokenUsageAccumulator(), null);

        loop.run(request);

        String response = request.messages().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .filter(item -> "call-write".equals(item.id()))
                .findFirst().orElseThrow().responseData();
        assertTrue(response.contains("\"code\":\"TOOL_ARGUMENTS_TRUNCATED\""));
        assertTrue(response.contains("writeFileChunk"));
    }

    @Test
    @DisplayName("自管循环最后一轮只暴露 finish，避免继续展开工作")
    void exposesOnlyFinishInFinalRound() {
        ReflectionTestUtils.setField(loop, "selfManagedLoopEnabled", true);
        when(profiles.resolve(ReasoningLevel.STANDARD)).thenReturn(
                new ReasoningProfile(true, "high", 2, 20_000, 1,
                        4_096, "deepseek-flash"));
        AssistantMessage.ToolCall read = new AssistantMessage.ToolCall(
                "call-read", "function", "readFile", "{\"path\":\"src/App.vue\"}");
        AssistantMessage.ToolCall finish = new AssistantMessage.ToolCall(
                "call-finish", "function", "finish", "{\"description\":\"done\"}");
        when(modelGateway.stream(any())).thenReturn(
                Flux.just(new ModelChunk("", "", List.of(read), "tool_calls",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")),
                Flux.just(new ModelChunk("", "", List.of(finish), "tool_calls",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));
        AtomicBoolean finished = new AtomicBoolean();
        when(finishCallback.call(anyString())).thenAnswer(invocation -> {
            finished.set(true);
            return "finished";
        });

        loop.run(request(new AtomicReference<>(""), finished,
                new TokenUsageAccumulator(), null));

        ArgumentCaptor<ModelRequest> requests = ArgumentCaptor.forClass(ModelRequest.class);
        verify(modelGateway, times(2)).stream(requests.capture());
        assertEquals(List.of("finish"), requests.getAllValues().get(1).tools().stream()
                .map(callback -> callback.getToolDefinition().name()).toList());
        assertTrue(finished.get());
    }

    @Test
    @DisplayName("轮数耗尽仍未 finish 时返回明确错误")
    void rejectsExhaustedLoopWithoutFinish() {
        ReflectionTestUtils.setField(loop, "selfManagedLoopEnabled", true);
        when(profiles.resolve(ReasoningLevel.STANDARD)).thenReturn(
                new ReasoningProfile(true, "high", 1, 20_000, 1,
                        4_096, "deepseek-flash"));
        AssistantMessage.ToolCall forged = new AssistantMessage.ToolCall(
                "call-read", "function", "readFile", "{\"path\":\"src/App.vue\"}");
        when(modelGateway.stream(any())).thenReturn(Flux.just(
                new ModelChunk("", "", List.of(forged), "tool_calls",
                        new ModelUsage(1, 1), "deepseek",
                        "deepseek-flash", "deepseek-flash")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> loop.run(request(new AtomicReference<>(""), new AtomicBoolean(),
                        new TokenUsageAccumulator(), null)));

        assertTrue(error.getMessage().contains("MAX_STEPS_EXCEEDED"));
    }

    private AgentToolLoop.ToolLoopRequest request(
            AtomicReference<String> description,
            AtomicBoolean finished,
            TokenUsageAccumulator usage,
            AgentProgressListener listener) {
        return new AgentToolLoop.ToolLoopRequest(
                CodeGenTypeEnum.HTML,
                7L,
                new ArrayList<>(List.of(new UserMessage("build a page"))),
                new LinkedHashMap<>(),
                finished,
                description,
                usage,
                AiCallContext.of(7L, 1L, "html"),
                listener,
                List.<ImageMaterialPlanner.Material>of(),
                ReasoningLevel.STANDARD);
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static WriteFileTool toolWithCallback(WriteFileTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any(), any(), any(), any())).thenReturn(callback);
        return tool;
    }

    private static WriteFileChunkTool toolWithCallback(WriteFileChunkTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any(), any(), any(), any())).thenReturn(callback);
        return tool;
    }

    private static EditFileTool toolWithCallback(EditFileTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any(), any(), any(), any())).thenReturn(callback);
        return tool;
    }

    private static ReadFileTool toolWithCallback(ReadFileTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any(), any())).thenReturn(callback);
        return tool;
    }

    private static SearchImageTool toolWithCallback(SearchImageTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any(), any())).thenReturn(callback);
        return tool;
    }

    private static DiagramTool toolWithCallback(DiagramTool tool, String name) {
        ToolCallback callback = callback(name);
        when(tool.build(any())).thenReturn(callback);
        return tool;
    }
}
