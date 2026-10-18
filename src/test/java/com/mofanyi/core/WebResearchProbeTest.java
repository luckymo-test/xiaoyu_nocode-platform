package com.mofanyi.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Search/Fetch 探针，默认跳过：
 * {@code mvn -o test -Dtest=WebResearchProbeTest -DcodegenProbe=true}
 */
@SpringBootTest(properties = {
        "codegen.web-research.enabled=true",
        "codegen.web-research.max-results=3"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class WebResearchProbeTest {

    @Autowired
    private WebResearchClient client;

    @Test
    @DisplayName("探针：DeepSeek Search 与 Fetch 返回真实内容")
    @SuppressWarnings("unchecked")
    void searchAndFetch() {
        Map<String, Object> search = client.search(
                "DeepSeek Search Fetch API 官方文档", 3, false,
                "查找 DeepSeek 网页搜索与抓取 API 文档");
        List<Map<String, Object>> results = (List<Map<String, Object>>) search.get("results");
        assertFalse(results.isEmpty(), "Search 应返回结果");
        System.out.println("[web-research-probe] searchResults=" + results.size()
                + " first=" + results.get(0));

        Map<String, Object> fetch = client.fetch(
                "https://api-docs.deepseek.com/zh-cn/guides/search");
        assertTrue(String.valueOf(fetch.get("content")).contains("Search"));
        System.out.println("[web-research-probe] fetchTitle=" + fetch.get("title")
                + " contentChars=" + String.valueOf(fetch.get("content")).length());
    }
}
