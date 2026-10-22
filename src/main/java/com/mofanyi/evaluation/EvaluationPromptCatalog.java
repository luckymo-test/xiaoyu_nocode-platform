package com.mofanyi.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定生成需求评估集；用于人工/离线基线，不进入普通单元测试的模型调用。
 */
@Component
public class EvaluationPromptCatalog {

    private final String version;
    private final List<EvaluationPrompt> prompts;

    public EvaluationPromptCatalog(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(
                "evaluation/generation-prompts.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            this.version = root.path("version").asText("1");
            List<EvaluationPrompt> values = new ArrayList<>();
            for (JsonNode node : root.path("prompts")) {
                List<String> capabilities = new ArrayList<>();
                node.path("requiredCapabilities").forEach(item -> capabilities.add(item.asText()));
                values.add(new EvaluationPrompt(
                        node.path("id").asText(),
                        node.path("category").asText(),
                        node.path("prompt").asText(),
                        capabilities,
                        node.path("viewportProfile").asText("default"),
                        node.path("weight").asInt(1)));
            }
            this.prompts = List.copyOf(values);
        } catch (Exception e) {
            throw new IllegalStateException("加载生成评估集失败", e);
        }
    }

    public String version() {
        return version;
    }

    public List<EvaluationPrompt> prompts() {
        return prompts;
    }
}
