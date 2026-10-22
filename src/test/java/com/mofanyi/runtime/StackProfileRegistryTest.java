package com.mofanyi.runtime;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackProfileRegistryTest {

    private final StackProfileRegistry registry = new StackProfileRegistry();

    @Test
    @DisplayName("auto 按兼容类型选择 Profile，已有 HTML/Vue 行为保持")
    void resolvesCompatibleProfile() {
        assertEquals("html-static",
                registry.resolve("auto", CodeGenTypeEnum.HTML).id());
        assertEquals("vue-vite",
                registry.resolve("auto", CodeGenTypeEnum.VUE).id());
    }

    @Test
    @DisplayName("未验证的 React/Next Profile 可发现但不能执行")
    void unavailableProfilesAreDiscoverableButRejected() {
        assertFalse(registry.get("react-vite-ts").available());
        assertFalse(registry.get("next-static-export").available());
        assertThrows(RuntimeException.class, () -> registry.require("react-vite-ts"));
        assertTrue(registry.list().stream().anyMatch(profile -> profile.id().equals("next-static-export")));
    }

    @Test
    @DisplayName("Profile 与生成类型不兼容时拒绝")
    void rejectsIncompatibleProfile() {
        assertThrows(RuntimeException.class,
                () -> registry.resolve("vue-vite", CodeGenTypeEnum.HTML));
    }
}
