package com.mofanyi.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 对话记忆配置：Redis 持久化 + 消息窗口。
 * conversationId 使用 appId，记忆与应用一一对应。
 *
 * <p>注意：maxMessages 只是**粗粒度上限**（防 Redis 无限增长）；真正决定"给模型注入多少上下文"
 * 的是 {@link com.mofanyi.ai.memory.AgentMemoryService} 里的 token 预算裁剪，不再以条数为准。
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 热窗口保留的最大消息条数（粗粒度上限，非注入上下文的口径）
     */
    private static final int MAX_WINDOW_MESSAGES = 60;

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(MAX_WINDOW_MESSAGES)
                .build();
    }
}
