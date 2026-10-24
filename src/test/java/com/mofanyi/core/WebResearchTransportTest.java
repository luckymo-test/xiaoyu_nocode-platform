package com.mofanyi.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebResearchTransportTest {

    @Test
    @DisplayName("Web Research 按 provider 选择 Transport，并保留缓存")
    void selectsConfiguredTransportAndCaches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode response = objectMapper.readTree("""
                {"results":[{"url":"https://example.com","title":"Example"}]}
                """);
        WebResearchTransport transport = mock(WebResearchTransport.class);
        when(transport.id()).thenReturn("test-provider");
        when(transport.post(eq("/v1/tools/search"), any())).thenReturn(response);

        WebResearchClient client = new WebResearchClient(List.of(transport));
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "provider", "test-provider");
        ReflectionTestUtils.setField(client, "maxResults", 5);
        ReflectionTestUtils.setField(client, "maxContentChars", 1000);
        ReflectionTestUtils.setField(client, "cacheTtlSeconds", 60L);
        ReflectionTestUtils.setField(client, "qps", 1000);

        Map<String, Object> first = client.search("cosplay", 1, false, null);
        Map<String, Object> second = client.search("cosplay", 1, false, null);

        assertEquals("test-provider_search", first.get("source"));
        assertEquals(first, second);
        verify(transport, times(1)).post(eq("/v1/tools/search"), any());
    }
}
