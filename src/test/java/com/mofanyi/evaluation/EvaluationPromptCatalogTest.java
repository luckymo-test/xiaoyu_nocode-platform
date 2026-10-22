package com.mofanyi.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationPromptCatalogTest {

    @Test
    @DisplayName("固定评估集包含 20 条唯一需求并覆盖 HTML/多文件/Vue")
    void containsVersionedTwentyPromptSet() {
        EvaluationPromptCatalog catalog = new EvaluationPromptCatalog(new ObjectMapper());

        assertEquals("1", catalog.version());
        assertEquals(20, catalog.prompts().size());
        assertEquals(20, new HashSet<>(catalog.prompts().stream()
                .map(EvaluationPrompt::id).toList()).size());
        assertTrue(catalog.prompts().stream().anyMatch(p -> p.id().startsWith("html-")));
        assertTrue(catalog.prompts().stream().anyMatch(p -> p.id().startsWith("multi-")));
        assertTrue(catalog.prompts().stream().anyMatch(p -> p.id().startsWith("vue-")));
    }
}
