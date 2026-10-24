package com.mofanyi.ai;

import com.mofanyi.ai.model.ModelChunk;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelUsage;
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
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentToolLoopGatewayTest {

    private ChatModel chatModel;
    private ModelGateway modelGateway;
    private ReasoningProfileRegistry profiles;
    private AiCallListener callListener;
    private FinishTool finishTool;
    private ToolCallback finishCallback;
    private AgentToolLoop loop;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        modelGateway = mock(ModelGateway.class);
        profiles = mock(ReasoningProfileRegistry.class);
        callListener = mock(AiCallListener.class);
        finishTool = mock(FinishTool.class);
        finishCallback = callback("finish");

        when(profiles.resolve(ReasoningLevel.STANDARD)).thenReturn(
                new ReasoningProfile(true, "high", 4, 20_000, 1,
                        4_096, "deepseek-flash"));
        when(finishTool.build(any(), any())).thenReturn(finishCallback);
        SearchWebTool searchWeb = mock(SearchWebTool.class);
        FetchWebTool fetchWeb = mock(FetchWebTool.class);
        when(searchWeb.isEnabled()).thenReturn(false);
        when(fetchWeb.isEnabled()).thenReturn(false);

        loop = new AgentToolLoop(
                chatModel,
                modelGateway,
                toolWithCallback(mock(WriteFileTool.class), "writeFile"),
                toolWithCallback(mock(ReadFileTool.class), "readFile"),
                toolWithCallback(mock(SearchImageTool.class), "searchImage"),
                searchWeb,
                fetchWeb,
                toolWithCallback(mock(DiagramTool.class), "diagram"),
                finishTool,
                mock(GenerationTaskRegistry.class),
                callListener,
                profiles);
        ReflectionTestUtils.setField(loop, "modelGatewayEnabled", true);
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
                eq("standard"), eq("deepseek"), eq("deepseek-flash"), eq("GENERATE"));
        verify(modelGateway).stream(any());
        verifyNoInteractions(chatModel);
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
        verifyNoInteractions(chatModel);
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
