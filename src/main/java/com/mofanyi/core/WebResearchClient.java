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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * DeepSeek Search / Fetch 原始 JSON API 客户端。
 *
 * <p>统一处理鉴权、账号级 QPS 限速、可重试错误、短 TTL 缓存和正文截断。
 * 返回内容是外部不可信数据，工具层会明确标记，不得当作系统指令执行。
 */
@Slf4j
@Component
public class WebResearchClient {

    private static final int SEARCH_QUERY_MAX_CHARS = 128;
    private static final int FETCH_URL_MAX_CHARS = 8192;
    private static final int CACHE_MAX_ENTRIES = 500;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong nextSearchSlot = new AtomicLong();
    private final AtomicLong nextFetchSlot = new AtomicLong();

    @Value("${codegen.web-research.enabled:false}")
    private boolean enabled;

    @Value("${codegen.web-research.max-results:5}")
    private int maxResults;

    @Value("${codegen.web-research.max-content-chars:6000}")
    private int maxContentChars;

    @Value("${codegen.web-research.cache-ttl-seconds:3600}")
    private long cacheTtlSeconds;

    @Value("${codegen.web-research.qps:5}")
    private int qps;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public WebResearchClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 网页搜索，返回带来源和权威度信息的结果。
     */
    public Map<String, Object> search(String query, Integer requestedCount, Boolean needContent,
                                      String originalQuestion) {
        ThrowUtils.throwIf(!enabled, ErrorCode.OPERATION_ERROR, "网页搜索工具未启用");
        String normalizedQuery = normalizeSearchQuery(query);
        int count = requestedCount == null ? maxResults
                : Math.max(1, Math.min(requestedCount, Math.min(maxResults, 50)));
        boolean withContent = Boolean.TRUE.equals(needContent);
        String original = StrUtil.maxLength(
                StrUtil.blankToDefault(originalQuestion, ""), SEARCH_QUERY_MAX_CHARS);
        String cacheKey = "search|" + count + "|" + withContent + "|" + original + "|" + normalizedQuery;
        return cached(cacheKey, () -> doSearch(normalizedQuery, count, withContent, original));
    }

    /**
     * 抓取明确给定的公开 HTTP/HTTPS URL，返回 Markdown 正文。
     */
    public Map<String, Object> fetch(String url) {
        ThrowUtils.throwIf(!enabled, ErrorCode.OPERATION_ERROR, "网页抓取工具未启用");
        String normalizedUrl = validateFetchUrl(url);
        return cached("fetch|" + normalizedUrl, () -> doFetch(normalizedUrl));
    }

    private Map<String, Object> doSearch(String query, int count, boolean needContent,
                                         String originalQuestion) {
        acquireSlot(nextSearchSlot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("count", count);
        body.put("need_content", needContent);
        if (StrUtil.isNotBlank(originalQuestion)) {
            body.put("ori_question", originalQuestion);
        }
        JsonNode root = post("/v1/tools/search", body);
        List<Map<String, Object>> results = new ArrayList<>();
        int remainingContent = Math.max(maxContentChars * Math.max(count, 1), maxContentChars);
        for (JsonNode item : root.path("results")) {
            if (results.size() >= count) {
                break;
            }
            String resultUrl = item.path("url").asText("");
            if (StrUtil.isBlank(resultUrl)) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("title", truncate(item.path("title").asText(""), 300));
            result.put("url", resultUrl);
            result.put("snippet", truncate(item.path("snippet").asText(""), 400));
            result.put("summary", truncate(item.path("summary").asText(""), 1200));
            result.put("siteName", nullableText(item.path("site_name")));
            result.put("date", nullableText(item.path("date")));
            result.put("authority", item.path("authority").asInt(-1));
            result.put("rerankScore", item.path("rerank_score").asDouble(0));
            String content = item.path("content").asText("");
            if (needContent && StrUtil.isNotBlank(content) && remainingContent > 0) {
                String clipped = content.substring(0, Math.min(content.length(), remainingContent));
                result.put("content", clipped);
                remainingContent -= clipped.length();
            } else {
                result.put("content", "");
            }
            results.add(result);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "deepseek_search");
        response.put("untrusted", true);
        response.put("query", query);
        response.put("results", results);
        log.info("[web-search] query={} count={} needContent={} 返回={}",
                query, count, needContent, results.size());
        return response;
    }

    private Map<String, Object> doFetch(String url) {
        acquireSlot(nextFetchSlot);
        JsonNode root = post("/v1/tools/fetch", Map.of("url", url));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "deepseek_fetch");
        response.put("untrusted", true);
        response.put("url", url);
        response.put("title", truncate(root.path("title").asText(""), 300));
        response.put("content", truncate(root.path("content").asText(""), maxContentChars));
        log.info("[web-fetch] url={} contentChars={}", url,
                String.valueOf(response.get("content")).length());
        return response;
    }

    private JsonNode post(String path, Map<String, Object> body) {
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

    static String validateFetchUrl(String url) {
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.PARAMS_ERROR, "抓取 URL 不能为空");
        ThrowUtils.throwIf(url.length() > FETCH_URL_MAX_CHARS, ErrorCode.PARAMS_ERROR,
                "抓取 URL 超过 8192 字符");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("抓取 URL 格式非法", e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || StrUtil.isBlank(host) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("抓取 URL 必须是无用户名密码的公开 HTTP/HTTPS 地址");
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".local")
                || "127.0.0.1".equals(lower) || "0.0.0.0".equals(lower)
                || "::1".equals(lower) || isPrivateIpv4(lower)) {
            throw new IllegalArgumentException("不允许抓取本机或内网地址");
        }
        return uri.toString();
    }

    private String normalizeSearchQuery(String query) {
        ThrowUtils.throwIf(StrUtil.isBlank(query), ErrorCode.PARAMS_ERROR, "搜索 query 不能为空");
        String trimmed = query.trim();
        ThrowUtils.throwIf(trimmed.codePointCount(0, trimmed.length()) > SEARCH_QUERY_MAX_CHARS,
                ErrorCode.PARAMS_ERROR, "搜索 query 超过 128 字符");
        return trimmed;
    }

    private Map<String, Object> cached(String key, RequestSupplier supplier) {
        long now = System.currentTimeMillis();
        CacheEntry existing = cache.get(key);
        if (existing != null && existing.expiresAt() > now) {
            return existing.value();
        }
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            if (cache.size() >= CACHE_MAX_ENTRIES) {
                cache.keySet().stream()
                        .limit(cache.size() - CACHE_MAX_ENTRIES + 1L)
                        .toList()
                        .forEach(cache::remove);
            }
        }
        Map<String, Object> value = supplier.get();
        cache.put(key, new CacheEntry(value,
                now + Math.max(cacheTtlSeconds, 1) * 1000L));
        return value;
    }

    private void acquireSlot(AtomicLong state) {
        long intervalNanos = 1_000_000_000L / Math.max(qps, 1);
        while (true) {
            long now = System.nanoTime();
            long current = state.get();
            long slot = Math.max(now, current);
            if (state.compareAndSet(current, slot + intervalNanos)) {
                LockSupport.parkNanos(Math.max(0, slot - now));
                return;
            }
        }
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        } catch (NumberFormatException e) {
            return false;
        }
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

    private String normalizeBaseUrl() {
        String value = StrUtil.blankToDefault(baseUrl, "https://api.deepseek.com");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…(已截断)";
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    @FunctionalInterface
    private interface RequestSupplier {
        Map<String, Object> get();
    }

    private record CacheEntry(Map<String, Object> value, long expiresAt) {
    }
}
