package com.mofanyi.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextCompressionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("超过阈值只压缩旧历史，保留最近轮次，并复用摘要缓存")
    void compressesOldHistoryAndReusesCache() {
        ChatModel chatModel = mock(ChatModel.class);
        CodeFileWriteService codeFileWriteService = mock(CodeFileWriteService.class);
        when(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, 7L))
                .thenReturn(tempDir.toString());
        when(chatModel.call(any(Prompt.class))).thenReturn(summaryResponse());

        ContextCompressionService service = new ContextCompressionService(
                chatModel, mock(ModelGateway.class), codeFileWriteService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minTokens", 1);
        ReflectionTestUtils.setField(service, "keepRecentRounds", 4);
        ReflectionTestUtils.setField(service, "maxOutputTokens", 1500);
        ReflectionTestUtils.setField(service, "compressionModel", "deepseek-flash");

        List<Message> raw = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            raw.add(new UserMessage("第" + i + "轮需求"));
            raw.add(new AssistantMessage("第" + i + "轮结果"));
        }

        List<Message> first = service.compressIfNeeded(CodeGenTypeEnum.HTML, 7L, raw);
        List<Message> second = service.compressIfNeeded(CodeGenTypeEnum.HTML, 7L, raw);

        assertEquals(9, first.size(), "1 条摘要 + 最近 4 轮原文");
        assertTrue(first.get(0) instanceof SystemMessage);
        assertTrue(first.get(0).getText().contains("最早目标"));
        assertTrue(first.get(0).getText().contains("数据边界"));
        assertEquals(first.size(), second.size());
        verify(chatModel, times(1)).call(any(Prompt.class));
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
        ChatModel chatModel = mock(ChatModel.class);
        ModelGateway modelGateway = mock(ModelGateway.class);
        CodeFileWriteService files = mock(CodeFileWriteService.class);
        when(files.getProjectDir(CodeGenTypeEnum.HTML, 8L))
                .thenReturn(tempDir.toString());
        when(modelGateway.chat(any())).thenReturn(new ModelResponse(
                summaryJson(), "", List.of(), "stop",
                new com.mofanyi.ai.model.ModelUsage(10, 5),
                "deepseek", "deepseek-flash", "deepseek-flash"));

        ContextCompressionService service = new ContextCompressionService(
                chatModel, modelGateway, files, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minTokens", 1);
        ReflectionTestUtils.setField(service, "keepRecentRounds", 4);
        ReflectionTestUtils.setField(service, "maxOutputTokens", 1500);
        ReflectionTestUtils.setField(service, "modelGatewayEnabled", true);

        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            messages.add(new UserMessage("第" + i + "轮需求"));
            messages.add(new AssistantMessage("第" + i + "轮结果"));
        }

        List<Message> compressed =
                service.compressIfNeeded(CodeGenTypeEnum.HTML, 8L, messages);

        assertTrue(compressed.get(0).getText().contains("最早目标"));
        verify(modelGateway).chat(any());
        verifyNoInteractions(chatModel);
    }

    private static ChatResponse summaryResponse() {
        String json = summaryJson();
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(json))))
                .build();
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
