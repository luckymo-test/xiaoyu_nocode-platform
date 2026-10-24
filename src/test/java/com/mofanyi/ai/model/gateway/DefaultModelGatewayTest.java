package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelCircuitBreaker;
import com.mofanyi.ai.model.ModelConcurrencyLimiter;
import com.mofanyi.ai.model.ModelDescriptor;
import com.mofanyi.ai.model.ModelProperties;
import com.mofanyi.ai.model.ModelRegistry;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.ai.model.ModelUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultModelGatewayTest {

    private ModelProperties properties;
    private ModelCircuitBreaker circuitBreaker;
    private ModelConcurrencyLimiter concurrencyLimiter;

    @BeforeEach
    void setUp() {
        properties = new ModelProperties();
        properties.getRouting().setEnabled(true);
        properties.setDefaultModel("primary");
        properties.setDefinitions(new LinkedHashMap<>(Map.of(
                "primary", definition("provider-a", "primary-model"),
                "secondary", definition("provider-b", "secondary-model")
        )));
        properties.setRoutes(new LinkedHashMap<>(Map.of("query-plan", "primary")));
        properties.setFallbacks(new LinkedHashMap<>(Map.of("primary", List.of("secondary"))));
        circuitBreaker = new ModelCircuitBreaker();
        concurrencyLimiter = new ModelConcurrencyLimiter();
    }

    @Test
    @DisplayName("主供应商异常时可降级到第二个 Adapter")
    void fallsBackToSecondProvider() {
        properties.getFallback().setEnabled(true);
        ModelAdapter primary = adapter("provider-a");
        ModelAdapter secondary = adapter("provider-b");
        when(primary.chat(any(), any())).thenThrow(new IllegalStateException("provider a down"));
        when(secondary.chat(any(), any())).thenReturn(new ModelResponse(
                "ok", "", List.of(), "stop", new ModelUsage(1, 1),
                "provider-b", "secondary", "secondary-model"));
        DefaultModelGateway gateway = gateway(List.of(primary, secondary));

        ModelResponse response = gateway.chat(request());

        assertEquals("provider-b", response.provider());
        verify(primary).chat(any(), any());
        verify(secondary).chat(any(), any());
    }

    @Test
    @DisplayName("未开任务路由时默认模型仍可使用 fallback")
    void defaultModelAlsoUsesFallbackChain() {
        properties.getRouting().setEnabled(false);
        properties.getFallback().setEnabled(true);
        ModelAdapter primary = adapter("provider-a");
        ModelAdapter secondary = adapter("provider-b");
        when(primary.chat(any(), any())).thenThrow(new IllegalStateException("provider a down"));
        when(secondary.chat(any(), any())).thenReturn(new ModelResponse(
                "ok", "", List.of(), "stop", new ModelUsage(1, 1),
                "provider-b", "secondary", "secondary-model"));
        DefaultModelGateway gateway = gateway(List.of(primary, secondary));

        ModelResponse response = gateway.chat(request());

        assertEquals("provider-b", response.provider());
        verify(secondary).chat(any(), any());
    }

    @Test
    @DisplayName("连续失败达到阈值后短路，不再调用供应商")
    void opensCircuitAfterFailureThreshold() {
        properties.getResilience().setFailureThreshold(2);
        properties.getResilience().setOpenDurationMs(60_000);
        ModelAdapter primary = adapter("provider-a");
        when(primary.chat(any(), any())).thenThrow(new IllegalStateException("down"));
        DefaultModelGateway gateway = gateway(List.of(primary));

        assertThrows(RuntimeException.class, () -> gateway.chat(request()));
        assertThrows(RuntimeException.class, () -> gateway.chat(request()));
        assertThrows(RuntimeException.class, () -> gateway.chat(request()));

        verify(primary, times(2)).chat(any(), any());
    }

    private DefaultModelGateway gateway(List<ModelAdapter> adapters) {
        return new DefaultModelGateway(
                new ModelRegistry(properties), adapters, properties,
                circuitBreaker, concurrencyLimiter);
    }

    private static ModelAdapter adapter(String provider) {
        ModelAdapter adapter = mock(ModelAdapter.class);
        when(adapter.supports(provider)).thenReturn(true);
        return adapter;
    }

    private static ModelRequest request() {
        return ModelRequest.textTask(
                ModelTask.QUERY_PLAN, List.of(new UserMessage("plan")), 0.1, 100);
    }

    private static ModelProperties.Definition definition(String provider, String modelName) {
        ModelProperties.Definition definition = new ModelProperties.Definition();
        definition.setProvider(provider);
        definition.setModelName(modelName);
        definition.setSupports(List.of(
                com.mofanyi.ai.model.ModelCapability.STREAMING,
                com.mofanyi.ai.model.ModelCapability.STRUCTURED_OUTPUT));
        return definition;
    }
}
