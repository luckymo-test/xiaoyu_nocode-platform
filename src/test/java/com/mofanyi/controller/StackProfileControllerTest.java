package com.mofanyi.controller;

import com.mofanyi.runtime.StackProfileRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackProfileControllerTest {

    @Test
    @DisplayName("Runtime API 同时暴露可用和待启用 Profile")
    void exposesRuntimeCapabilities() {
        StackProfileController controller =
                new StackProfileController(new StackProfileRegistry(true, false));

        var profiles = controller.list().getData();
        assertEquals(5, profiles.size());
        assertTrue(profiles.stream().anyMatch(profile ->
                "react-vite-ts".equals(profile.getId()) && profile.isAvailable()));
        assertFalse(profiles.stream().filter(profile ->
                "next-static-export".equals(profile.getId())).findFirst()
                .orElseThrow().isAvailable());
    }
}
