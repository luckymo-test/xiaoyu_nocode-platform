package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
public class DeepSeekWebResearchTransport implements WebResearchTransport {

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public DeepSeekWebResearchTransport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public String id() {
        return "deepseek";
    }

    @Override
    public JsonNode post(String path, Map<String, Object> body) {
        ThrowUtils.throwIf(StrUtil.isBlank(apiKey), ErrorCode.OPERATION_ERROR,
                "网页研究 API Key 未配置");
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = restClient.post()
                        .uri(normalizeBaseUrl() + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return objectMapper.readTree(response);
            } catch (RestClientResponseException e) {
                lastError = e;
                int status = e.getStatusCode().value();
                if (!isRetryable(status) || attempt == 3) {
                    throw e;
                }
                sleep(300L * attempt);
            } catch (Exception e) {
                lastError = e instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException(e);
                if (attempt == 3) {
                    break;
                }
                sleep(300L * attempt);
            }
        }
        throw new IllegalStateException("网页研究请求失败", lastError);
    }

    private String normalizeBaseUrl() {
        String value = StrUtil.blankToDefault(baseUrl, "https://api.deepseek.com");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isRetryable(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(millis, 2_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("网页研究重试被中断", e);
        }
    }
}
