package com.mofanyi.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis 的 ChatMemoryRepository 实现（Spring AI 官方未提供 Redis 仓库）。
 * 存储键：spring:ai:chat:memory:{conversationId}，值为 JSON 数组，
 * 每项 {type, text} 表示一条消息。conversationId 在本项目中为 appId。
 */
@Repository
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "spring:ai:chat:memory:";
    private static final TypeReference<List<ChatMemoryEntry>> ENTRY_LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        // 用 SCAN 遍历前缀 key，避免 KEYS 在大数据量下阻塞 Redis
        redisTemplate.execute((RedisCallback<List<String>>) connection -> {
            try {
                // scan() 在资源初始化阶段抛的检查异常不会被内层 try 的 catch 捕获，
                // 因此外层再包一层 try。Cursor 的 close() 声明为 throws Exception，需捕获 Exception
                try (Cursor<byte[]> cursor = connection.scan(
                        ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
                    while (cursor.hasNext()) {
                        String key = new String(cursor.next(), StandardCharsets.UTF_8);
                        ids.add(key.substring(KEY_PREFIX.length()));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("扫描对话记忆失败", e);
            }
            return null;
        });
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            List<ChatMemoryEntry> entries = objectMapper.readValue(json, ENTRY_LIST_TYPE);
            return entries.stream().map(ChatMemoryEntry::toMessage).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Redis 对话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<ChatMemoryEntry> entries = messages.stream().map(ChatMemoryEntry::fromMessage).toList();
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, objectMapper.writeValueAsString(entries));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化对话记忆失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }

    /**
     * 消息的可序列化载体：仅保留类型与文本，避免直接序列化 Spring AI 的 Message 多态类型。
     */
    private record ChatMemoryEntry(String type, String text) {

        static ChatMemoryEntry fromMessage(Message message) {
            return new ChatMemoryEntry(message.getMessageType().name().toLowerCase(), message.getText());
        }

        Message toMessage() {
            String text = this.text == null ? "" : this.text;
            try {
                return switch (MessageType.valueOf(this.type.toUpperCase())) {
                    case ASSISTANT -> new AssistantMessage(text);
                    case SYSTEM -> new SystemMessage(text);
                    default -> new UserMessage(text);
                };
            } catch (IllegalArgumentException e) {
                // 未知类型兜底为用户消息，保证反序列化不中断
                return new UserMessage(text);
            }
        }
    }
}
