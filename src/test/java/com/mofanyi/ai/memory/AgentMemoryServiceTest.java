package com.mofanyi.ai.memory;

import com.mofanyi.entity.ChatHistory;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.service.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentMemoryService} 单元测试：覆盖 读缓存 / 冷启动回灌 / 写入 / 去代码化 / token 裁剪。
 * 不依赖真实 Redis 与 MySQL（全部 mock）。
 */
@ExtendWith(MockitoExtension.class)
class AgentMemoryServiceTest {

    private static final long APP_ID = 1L;
    private static final String CONVERSATION_ID = "1";

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private ChatHistoryService chatHistoryService;

    private AgentMemoryService memoryService;

    @BeforeEach
    void setUp() {
        memoryService = new AgentMemoryService(chatMemory, chatHistoryService);
    }

    @Test
    @DisplayName("缓存命中时直接返回，不查历史")
    void loadContext_hitsCache() {
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(List.of(new UserMessage("你好")));

        List<Message> context = memoryService.loadContext(APP_ID, "你好");

        assertEquals(1, context.size());
        assertEquals("你好", context.get(0).getText());
        verifyNoInteractions(chatHistoryService);
    }

    @Test
    @DisplayName("冷启动：缓存为空时从 chat_history 回灌，并剥掉代码块 / 跳过错误消息，再写回缓存")
    void loadContext_rehydratesFromHistory() {
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(List.of());
        when(chatHistoryService.listRecentByAppId(APP_ID, 30)).thenReturn(List.of(
                history(ChatMessageTypeEnum.USER, "做一个登录页"),
                history(ChatMessageTypeEnum.ASSISTANT, "已完成\n```html\n<div>x</div>\n```"),
                history(ChatMessageTypeEnum.ERROR, "生成失败：timeout")));

        List<Message> context = memoryService.loadContext(APP_ID, "做一个登录页");

        assertEquals(2, context.size(), "错误消息不应回灌进模型上下文");
        assertEquals("做一个登录页", context.get(0).getText());
        assertFalse(context.get(1).getText().contains("```"), "回灌时 AI 回复里的代码块必须被剥离");
        assertTrue(context.get(1).getText().contains("[代码已省略"), "应留下省略标记");
        verify(chatMemory).add(eq(CONVERSATION_ID), anyList());
    }

    @Test
    @DisplayName("历史与缓存都为空时返回空上下文（不抛异常）")
    void loadContext_emptyWhenNothingToRecall() {
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(List.of());
        when(chatHistoryService.listRecentByAppId(APP_ID, 30)).thenReturn(List.of());

        assertTrue(memoryService.loadContext(APP_ID, "随便聊聊").isEmpty());
    }

    @Test
    @DisplayName("回灌时丢掉尾部的本轮用户消息（controller 已提前落库），避免与调用方重复追加")
    void loadContext_dropsTrailingCurrentUserMessage() {
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(List.of());
        when(chatHistoryService.listRecentByAppId(APP_ID, 30)).thenReturn(List.of(
                history(ChatMessageTypeEnum.USER, "第一轮需求"),
                history(ChatMessageTypeEnum.ASSISTANT, "已完成"),
                history(ChatMessageTypeEnum.USER, "把按钮改成蓝色")));

        List<Message> context = memoryService.loadContext(APP_ID, "把按钮改成蓝色");

        assertEquals(2, context.size(), "尾部本轮用户消息应被去掉");
        assertEquals("第一轮需求", context.get(0).getText());
        assertEquals("已完成", context.get(1).getText());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("记录一轮对话：写入 user + assistant 两条消息")
    void appendTurn_writesBothMessages() {
        memoryService.appendTurn(APP_ID, "把按钮改成蓝色", "已完成修改，产出文件：index.html。");

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq(CONVERSATION_ID), captor.capture());
        List<Message> written = captor.getValue();
        assertEquals(2, written.size());
        assertEquals("把按钮改成蓝色", written.get(0).getText());
        assertEquals("已完成修改，产出文件：index.html。", written.get(1).getText());
    }

    @Test
    @DisplayName("token 预算裁剪：超预算时从最旧的消息开始丢弃，保留最近的")
    void loadContext_trimsOldestWhenOverBudget() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("最旧的消息"));
        for (int i = 0; i < 20; i++) {
            messages.add(new UserMessage("x".repeat(2000)));
        }
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(messages);

        List<Message> context = memoryService.loadContext(APP_ID, "再改一处");

        assertTrue(context.size() < messages.size(), "超预算应裁剪");
        assertNotNull(context);
        assertFalse(context.isEmpty());
        assertTrue(context.stream().noneMatch(m -> "最旧的消息".equals(m.getText())),
                "应丢弃最旧的消息");
    }

    @Test
    @DisplayName("token 估算与代码块剥离的基本行为")
    void helpers() {
        assertEquals(0, AgentMemoryService.estimateTokens(null));
        assertEquals(0, AgentMemoryService.estimateTokens(""));
        assertTrue(AgentMemoryService.estimateTokens("你好世界") >= 4, "中文按 1 字约 1 token");

        assertEquals("[代码已省略，见工程文件]",
                AgentMemoryService.stripCodeBlocks("```html\n<div>x</div>\n```"));
    }

    private static ChatHistory history(ChatMessageTypeEnum type, String message) {
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setMessageType(type.getValue());
        chatHistory.setMessage(message);
        return chatHistory;
    }
}
