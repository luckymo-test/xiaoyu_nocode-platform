package com.mofanyi.ai;

import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfile;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningProfileRegistryTest {

    @Test
    @DisplayName("推理档位别名归一化为四个有效档位")
    void resolvesAliases() {
        ReasoningProfileRegistry registry = new ReasoningProfileRegistry();
        assertEquals(ReasoningLevel.ECO, registry.parseLevel("low"));
        assertEquals(ReasoningLevel.STANDARD, registry.parseLevel("medium"));
        assertEquals(ReasoningLevel.MAX, registry.parseLevel("ultra"));
        assertEquals(ReasoningLevel.FAST, registry.parseLevel("disabled"));
    }

    @Test
    @DisplayName("配置映射优先于代码兜底，并保留 thinking off")
    void resolvesConfiguredProfile() {
        ReasoningProfileRegistry registry = new ReasoningProfileRegistry();
        ReasoningProfile fast = new ReasoningProfile(false, null, 6, 100_000L, 1, 16_384, "deepseek-flash");
        registry.setProfiles(Map.of("fast", fast));

        ReasoningProfile resolved = registry.resolve(ReasoningLevel.FAST);

        assertFalse(resolved.thinkingEnabled());
        assertEquals(6, resolved.maxSteps());
        assertEquals(16_384, resolved.maxOutputTokens());
        assertEquals(ReasoningProfile.DEFAULT_MAX_DURATION_SECONDS,
                resolved.maxDurationSeconds());
        assertTrue(registry.resolve("max").thinkingEnabled());
        assertEquals(2_000_000, registry.resolve("max").maxLoopTokens());
        assertEquals(1_500, registry.resolve("max").maxDurationSeconds());
    }

    @Test
    @DisplayName("非法档位返回参数错误")
    void rejectsUnknownLevel() {
        ReasoningProfileRegistry registry = new ReasoningProfileRegistry();
        assertThrows(BusinessException.class, () -> registry.parseLevel("turbo"));
    }
}
