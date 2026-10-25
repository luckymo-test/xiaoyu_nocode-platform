package com.mofanyi.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRegistryTest {

    private final TemplateRegistry registry = new TemplateRegistry();

    @Test
    @DisplayName("摄影需求自动选择摄影模板，普通需求选择中性模板")
    void autoSelectsTemplateByIntent() {
        assertEquals("photography-editorial",
                registry.resolve("auto", "做一个摄影作品照片墙", "html-static").id());
        assertEquals("default-neutral",
                registry.resolve("auto", "做一个企业介绍页", "html-static").id());
        assertEquals("default-neutral",
                registry.resolve("auto", "做一个企业介绍页", "react-vite-ts").id());
        assertEquals("photography-editorial",
                registry.resolve("auto", "做一个摄影作品照片墙", "next-static-export").id());
        assertEquals("data-dashboard",
                registry.resolve("auto", "我要一个销售数据看板", "react-vite-ts").id());
        assertEquals("saas-product",
                registry.resolve("auto", "做一个 SaaS 产品落地页", "next-static-export").id());
        assertEquals("portfolio-studio",
                registry.resolve("auto", "做一个设计师个人作品集", "vue-vite").id());
    }

    @Test
    @DisplayName("模板带稳定有效哈希，版本锁定可用于 Job/Revision")
    void computesStableEffectiveHash() {
        TemplateBundle template = registry.get("photography-editorial");
        assertEquals(64, template.effectiveHash().length());
        assertFalse(template.sectionIds().isEmpty());
        assertTrue(template.designTokens().containsKey("color.accent"));
    }

    @Test
    @DisplayName("未知或不兼容模板被拒绝")
    void rejectsUnknownTemplate() {
        assertThrows(RuntimeException.class,
                () -> registry.resolve("missing", "任意", "html-static"));
    }

    @Test
    @DisplayName("所有视觉模板兼容全部可用 StackProfile 并包含完整视觉令牌")
    void templatesSupportAllRuntimeProfiles() {
        for (TemplateBundle template : registry.list()) {
            assertTrue(template.compatibleProfiles().containsAll(java.util.Set.of(
                    "html-static", "multi-file-static", "vue-vite",
                    "react-vite-ts", "next-static-export")));
            assertTrue(template.designTokens().containsKey("grid"));
            assertTrue(template.designTokens().containsKey("type.scale"));
            assertTrue(template.designTokens().containsKey("image.rhythm"));
            assertTrue(template.designTokens().containsKey("motion.policy"));
        }
    }
}
