package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.tools.FetchWebTool;
import com.mofanyi.ai.tools.SearchWebTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebResearchClientTest {

    @Test
    @DisplayName("Fetch URL 仅允许无凭据的公开 HTTP/HTTPS 地址")
    void validatesFetchUrl() {
        assertEquals("https://example.com/a",
                WebResearchClient.validateFetchUrl("https://example.com/a"));
        assertThrows(IllegalArgumentException.class,
                () -> WebResearchClient.validateFetchUrl("http://localhost:8080/private"));
        assertThrows(IllegalArgumentException.class,
                () -> WebResearchClient.validateFetchUrl("http://192.168.1.10/admin"));
        assertThrows(IllegalArgumentException.class,
                () -> WebResearchClient.validateFetchUrl("https://user:pass@example.com/a"));
        assertThrows(IllegalArgumentException.class,
                () -> WebResearchClient.validateFetchUrl("file:///etc/passwd"));
    }

    @Test
    @DisplayName("searchWeb 与 fetchWeb 工具返回结构化 JSON")
    void toolsReturnStructuredJson() {
        WebResearchClient client = mock(WebResearchClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.search("DeepSeek", 2, false, "原始问题"))
                .thenReturn(Map.of("untrusted", true, "results", java.util.List.of()));
        when(client.fetch("https://example.com/a"))
                .thenReturn(Map.of("untrusted", true, "title", "Example"));
        ObjectMapper mapper = new ObjectMapper();

        ToolCallback search = new SearchWebTool(client, mapper).build();
        ToolCallback fetch = new FetchWebTool(client, mapper).build();

        String searchResult = search.call(
                "{\"query\":\"DeepSeek\",\"count\":2,\"needContent\":false,\"originalQuestion\":\"原始问题\"}");
        String fetchResult = fetch.call("{\"url\":\"https://example.com/a\"}");

        assertTrue(searchResult.contains("\"untrusted\":true"));
        assertTrue(fetchResult.contains("\"title\":\"Example\""));
    }
}
