package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.job.GenerationExecutionCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Conditional;

import java.time.Duration;
import java.util.Map;

/**
 * Redis Streams 异步传输：Java 提交任务，Playwright Worker 消费并回写结果键。
 */
@Component
@Conditional(GenerationExecutionCondition.class)
@ConditionalOnProperty(name = "codegen.browser.playwright.transport",
        havingValue = "redis")
public class RedisBrowserVerificationTransport implements BrowserVerificationTransport {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String stream;
    private final String resultPrefix;
    private final long timeoutMillis;
    private final long pollMillis;

    public RedisBrowserVerificationTransport(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${codegen.browser.playwright.redis.stream:codegen:browser-verify:requests}")
            String stream,
            @Value("${codegen.browser.playwright.redis.result-prefix:codegen:browser-verify:result:}")
            String resultPrefix,
            @Value("${codegen.browser.playwright.read-timeout-ms:120000}") long timeoutMillis,
            @Value("${codegen.browser.playwright.redis.poll-millis:200}") long pollMillis) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.stream = stream;
        this.resultPrefix = resultPrefix;
        this.timeoutMillis = Math.max(5_000, timeoutMillis);
        this.pollMillis = Math.max(50, pollMillis);
    }

    @Override
    public String execute(Map<String, Object> request) {
        String verificationId = String.valueOf(request.get("verificationId"));
        try {
            String payload = objectMapper.writeValueAsString(request);
            redisTemplate.opsForStream().add(stream, Map.of(
                    "verificationId", verificationId,
                    "payload", payload));
        } catch (Exception e) {
            throw new IllegalStateException("提交 Playwright 验证任务失败", e);
        }

        String resultKey = resultPrefix + verificationId;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String result = redisTemplate.opsForValue().get(resultKey);
            if (result != null && !result.isBlank()) {
                redisTemplate.delete(resultKey);
                return result;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Playwright 验证结果被中断", e);
            }
        }
        redisTemplate.expire(resultKey, Duration.ofSeconds(1));
        throw new IllegalStateException("Playwright 验证结果超时：" + verificationId);
    }
}
