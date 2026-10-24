package com.mofanyi.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.AiCallListener;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompressionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("超过阈值只压缩旧历史，保留最近轮次，并复用摘要缓存")
    void compressesOldHistoryAndReusesCache() {
        ModelGateway modelGateway = mock(ModelGateway.class);
        AiCallListener callListener = mock(AiCallListener.class);
        CodeFileWriteService codeFileWriteService = mock(CodeFileWriteService.class);
        when(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, 7L))
                .thenReturn(tempDir.toString());
        when(modelGateway.chat(any())).thenReturn(summaryResponse());

        ContextCompressionService service = new ContextCompressionService(
                modelGateway, callListener, codeFileWriteService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minTokens", 1);
        ReflectionTestUtils.setField(service, "keepRecentRounds", 4);
        ReflectionTestUtils.setField(service, "maxOutputTokens", 1500);

        List<Message> raw = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            raw.add(new UserMessage("第" + i + "轮需求"));
            raw.add(new AssistantMessage("第" + i + "轮结果"));
        }

        AiCallContext context = AiCallContext.ofJob(7L, 3L, "html", 99L);
        List<Message> first = service.compressIfNeeded(
                CodeGenTypeEnum.HTML, 7L, raw, context);
        List<Message> second = service.compressIfNeeded(
                CodeGenTypeEnum.HTML, 7L, raw, context);

        assertEquals(9, first.size(), "1 条摘要 + 最近 4 轮原文");
        assertTrue(first.get(0) instanceof SystemMessage);
        assertTrue(first.get(0).getText().contains("最早目标"));
        assertTrue(first.get(0).getText().contains("数据边界"));
        assertEquals(first.size(), second.size());
        verify(modelGateway).chat(any());
        verify(callListener).onSuccess(eq(context), eq("stop"), eq(10L), eq(5L),
                anyLong(), eq("standard"), eq("deepseek"),
                eq("deepseek-flash"), eq("deepseek-flash"),
                eq(ModelTask.COMPRESS.name()));
        assertTrue(Files.exists(tempDir.resolve(".agent").resolve("context-summary.json")));
    }

    @Test
    @DisplayName("轮次切分从最近第 N 条用户消息开始")
    void findsRecentRoundBoundary() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(new UserMessage("u" + i));
            messages.add(new AssistantMessage("a" + i));
        }

        assertEquals(4, ContextCompressionService.recentRoundStart(messages, 4));
    }

    @Test
    @DisplayName("ModelGateway 开启时压缩不再直接调用 ChatModel")
    void usesModelGatewayWhenEnabled() {
        ModelGateway modelGateway = mock(ModelGateway.class);
        CodeFileWriteService files = mock(CodeFileWriteService.class);
        when(files.getProjectDir(CodeGenTypeEnum.HTML, 8L))
                .thenReturn(tempDir.toString());
        when(modelGateway.chat(any())).thenReturn(new ModelResponse(
                summaryJson(), "", List.of(), "stop",
                new com.mofanyi.ai.model.ModelUsage(10, 5),
                "deepseek", "deepseek-flash", "deepseek-flash"));

        ContextCompressionService service = new ContextCompressionService(
                modelGateway, mock(AiCallListener.class), files, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minTokens", 1);
        ReflectionTestUtils.setField(service, "keepRecentRounds", 4);
        ReflectionTestUtils.setField(service, "maxOutputTokens", 1500);

        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            messages.add(new UserMessage("第" + i + "轮需求"));
            messages.add(new AssistantMessage("第" + i + "轮结果"));
        }

        List<Message> compressed =
                service.compressIfNeeded(CodeGenTypeEnum.HTML, 8L, messages);

        assertTrue(compressed.get(0).getText().contains("最早目标"));
        verify(modelGateway).chat(any());
    }

    private static ModelResponse summaryResponse() {
        return new ModelResponse(summaryJson(), "", List.of(), "stop",
                new com.mofanyi.ai.model.ModelUsage(10, 5),
                "deepseek", "deepseek-flash", "deepseek-flash");
    }

    private static String summaryJson() {
        return """
                {
                  "objective":"最早目标",
                  "constraints":["保持简洁"],
                  "decisions":["使用深色主题"],
                  "rejected":[],
                  "completed":["完成首页"],
                  "pending":["增加联系表单"],
                  "lastFocus":"调整作品卡片"
                }
                """;
    }
}
