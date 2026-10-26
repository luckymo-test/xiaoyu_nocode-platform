package com.mofanyi.runtime;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RuntimeApiPromptService {

    private final String template;

    public RuntimeApiPromptService() {
        try {
            template = new ClassPathResource("prompt/runtime_data_api.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载 Data/API Prompt 失败", e);
        }
    }

    public String section(Long appId) {
        return template.formatted(appId);
    }
}
