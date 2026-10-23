package com.mofanyi.ai.model;

import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRegistryTest {

    private ModelProperties properties;
    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new ModelProperties();
        properties.setDefaultModel("text-model");
        properties.setDefinitions(new LinkedHashMap<>(Map.of(
                "text-model", definition("openai-compatible", "planner",
                        ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT),
                "vision-model", definition("deepseek", "deepseek-vision",
                        ModelCapability.VISION, ModelCapability.STREAMING)
        )));
        properties.setRoutes(new LinkedHashMap<>(Map.of(
                "vision-curate", "vision-model",
                "query-plan", "text-model"
        )));
        registry = new ModelRegistry(properties);
    }

    @Test
    @DisplayName("按任务路由到具备视觉能力的模型")
    void routesVisionTaskByCapability() {
        ModelDescriptor descriptor = registry.route(
                ModelTask.VISION_CURATE, Set.of(), 1_000, 1_000);

        assertEquals("vision-model", descriptor.id());
    }

    @Test
    @DisplayName("路由目标缺少任务必需能力时拒绝调用")
    void rejectsModelWithoutRequiredCapability() {
        properties.getRoutes().put("vision-curate", "text-model");

        assertThrows(BusinessException.class, () -> registry.route(
                ModelTask.VISION_CURATE, Set.of(), 1_000, 1_000));
    }

    @Test
    @DisplayName("上下文窗口不足时在调用前拒绝")
    void rejectsInsufficientContextWindow() {
        assertThrows(BusinessException.class, () -> registry.route(
                ModelTask.QUERY_PLAN, Set.of(), 31_000, 2_000));
    }

    @Test
    @DisplayName("fallback 只追加已启用且能力匹配的候选模型")
    void appendsCapableFallbackCandidates() {
        ModelProperties.Definition fallback = definition("openai-compatible", "backup",
                ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT);
        properties.getDefinitions().put("backup-model", fallback);
        properties.getFallback().setEnabled(true);
        properties.getFallbacks().put("text-model", List.of("vision-model", "backup-model"));

        List<ModelDescriptor> candidates = registry.routeCandidates(
                ModelTask.QUERY_PLAN, Set.of(), 1_000, 1_000);

        assertEquals(List.of("text-model", "backup-model"),
                candidates.stream().map(ModelDescriptor::id).toList());
    }

    private static ModelProperties.Definition definition(
            String provider, String modelName, ModelCapability... capabilities) {
        ModelProperties.Definition definition = new ModelProperties.Definition();
        definition.setProvider(provider);
        definition.setModelName(modelName);
        definition.setContextWindow(32_000);
        definition.setMaxOutputTokens(4_096);
        definition.setSupports(List.of(capabilities));
        return definition;
    }
}
