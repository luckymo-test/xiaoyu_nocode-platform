package com.mofanyi.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePromptServiceTest {

    @Test
    @DisplayName("Job 上下文中的模板版本、区块和设计 Token 注入 Prompt")
    void buildsVersionedPromptSection() {
        TemplateRegistry registry = new TemplateRegistry();
        TemplateContext context = new TemplateContext();
        TemplateBundle template = registry.get("photography-editorial");
        context.activate(7L, template);

        String section = new TemplatePromptService(context).buildSection(7L);

        assertTrue(section.contains("photography-editorial"));
        assertTrue(section.contains("version=3"));
        assertTrue(section.contains("type.scale="));
        assertTrue(section.contains("必须把 designTokens 落实为实际 CSS/Tailwind 设计令牌"));
        assertTrue(section.contains("masonry-gallery"));
        assertTrue(section.contains("#c6a869"));
        context.deactivate(7L, template);
    }
}
