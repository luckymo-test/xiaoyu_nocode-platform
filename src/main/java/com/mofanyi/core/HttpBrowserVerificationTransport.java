package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.job.GenerationExecutionCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
@Conditional(GenerationExecutionCondition.class)
@ConditionalOnProperty(name = "codegen.browser.playwright.transport",
        havingValue = "http", matchIfMissing = true)
public class HttpBrowserVerificationTransport implements BrowserVerificationTransport {

    private final RestClient restClient;
    private final String workerUrl;

    public HttpBrowserVerificationTransport(
            @Value("${codegen.browser.playwright.url:http://127.0.0.1:8090}") String workerUrl,
            @Value("${codegen.browser.playwright.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${codegen.browser.playwright.read-timeout-ms:120000}") int readTimeoutMs) {
        this.workerUrl = StrUtil.removeSuffix(workerUrl, "/");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(5_000, readTimeoutMs)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public String execute(Map<String, Object> request) {
        return restClient.post()
                .uri(workerUrl + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
