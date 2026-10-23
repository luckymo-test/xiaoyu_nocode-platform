package com.mofanyi.ai.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPropertiesBindingTest {

    @Test
    @DisplayName("YAML 风格的模型能力、任务路由与开关可以正确绑定")
    void bindsRegistryConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("codegen.model.gateway.enabled", "true")
                .withProperty("codegen.model.routing.enabled", "true")
                .withProperty("codegen.model.default-model", "deepseek-flash")
                .withProperty("codegen.model.definitions.deepseek-flash.provider", "deepseek")
                .withProperty("codegen.model.definitions.deepseek-flash.model-name", "deepseek-flash")
                .withProperty("codegen.model.definitions.deepseek-flash.context-window", "128000")
                .withProperty("codegen.model.definitions.deepseek-flash.max-output-tokens", "65536")
                .withProperty("codegen.model.definitions.deepseek-flash.supports[0]", "tools")
                .withProperty("codegen.model.definitions.deepseek-flash.supports[1]", "vision")
                .withProperty("codegen.model.definitions.deepseek-flash.supports[2]", "structured-output")
                .withProperty("codegen.model.routes.query-plan", "deepseek-flash");

        ModelProperties properties = Binder.get(environment)
                .bind("codegen.model", Bindable.of(ModelProperties.class))
                .get();
        ModelRegistry registry = new ModelRegistry(properties);
        ModelDescriptor descriptor = registry.route(
                ModelTask.QUERY_PLAN, Set.of(), 1_000, 500);

        assertTrue(properties.getGateway().isEnabled());
        assertTrue(properties.getRouting().isEnabled());
        assertEquals("deepseek", descriptor.provider());
        assertEquals(128_000, descriptor.contextWindow());
        assertTrue(descriptor.supports(ModelCapability.VISION));
    }
}
