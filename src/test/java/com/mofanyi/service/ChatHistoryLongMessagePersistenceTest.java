package com.mofanyi.service;

import com.mofanyi.entity.ChatHistory;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实数据库迁移探针，默认跳过：
 * {@code mvn -o test -Dtest=ChatHistoryLongMessagePersistenceTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ChatHistoryLongMessagePersistenceTest {

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Test
    @Transactional
    @DisplayName("chat_history 可保存超过 TEXT 上限的生成结果")
    void savesMessageLongerThanTextLimit() {
        String message = "x".repeat(100_000);

        ChatHistory saved = chatHistoryService.saveMessage(
                990001000000000005L, 1L, message,
                ChatMessageTypeEnum.ASSISTANT.getValue(), null);

        assertEquals(100_000, saved.getMessage().length());
    }
}
