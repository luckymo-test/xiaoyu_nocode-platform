package com.mofanyi.ai;

import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfile;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        ReasoningProfile fast = new ReasoningProfile(
                false, null, 6, 100_000L, 1, 16_384, "deepseek-flash", 900);
        registry.setProfiles(Map.of("fast", fast));

        ReasoningProfile resolved = registry.resolve(ReasoningLevel.FAST);

        assertFalse(resolved.thinkingEnabled());
        assertEquals(6, resolved.maxSteps());
        assertEquals(16_384, resolved.maxOutputTokens());
        assertEquals(ReasoningProfile.DEFAULT_MAX_DURATION_SECONDS,
                resolved.maxDurationSeconds());
        assertEquals(100_000, resolved.maxLoopTokens(), "显式配置必须覆盖代码兜底值");

        ReasoningProfileRegistry fallback = new ReasoningProfileRegistry();
        assertEquals(48, fallback.resolve("fast").maxSteps());
        assertEquals(3_000_000, fallback.resolve("fast").maxLoopTokens());
        assertEquals(80, fallback.resolve("eco").maxSteps());
        assertEquals(6_000_000, fallback.resolve("eco").maxLoopTokens());
        assertEquals(160, fallback.resolve("standard").maxSteps());
        assertEquals(16_000_000, fallback.resolve("standard").maxLoopTokens());
        assertTrue(fallback.resolve("max").thinkingEnabled());
        assertEquals(240, fallback.resolve("max").maxSteps());
        assertEquals(24_000_000, fallback.resolve("max").maxLoopTokens());
        assertEquals(4_800, fallback.resolve("max").maxDurationSeconds());
    }

    @Test
    @DisplayName("非法档位返回参数错误")
    void rejectsUnknownLevel() {
        ReasoningProfileRegistry registry = new ReasoningProfileRegistry();
        assertThrows(BusinessException.class, () -> registry.parseLevel("turbo"));
    }

    @Test
    @DisplayName("Spring Boot 能绑定 kebab-case 的 thinking-enabled 配置")
    void bindsKebabCaseProperties() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "codegen.reasoning.default-level=eco",
                        "codegen.reasoning.profiles.fast.thinking-enabled=false",
                        "codegen.reasoning.profiles.fast.max-steps=21",
                        "codegen.reasoning.profiles.fast.max-loop-tokens=1400000",
                        "codegen.reasoning.profiles.fast.repair-rounds=1",
                        "codegen.reasoning.profiles.fast.max-output-tokens=32768",
                        "codegen.reasoning.profiles.fast.model=deepseek-flash",
                        "codegen.reasoning.profiles.fast.max-duration-seconds=777")
                .run(context -> {
                    ReasoningProfileRegistry registry =
                            context.getBean(ReasoningProfileRegistry.class);
                    ReasoningProfile fast = registry.resolve(ReasoningLevel.FAST);
                    assertEquals(ReasoningLevel.ECO, registry.defaultReasoningLevel());
                    assertFalse(fast.thinkingEnabled());
                    assertFalse(fast.isThinkingEnabled());
                    assertNull(fast.effort());
                    assertNull(fast.getEffort());
                    assertEquals(21, fast.maxSteps());
                    assertEquals(21, fast.getMaxSteps());
                    assertEquals(1_400_000, fast.maxLoopTokens());
                    assertEquals(1_400_000, fast.getMaxLoopTokens());
                    assertEquals(777, fast.maxDurationSeconds());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReasoningProfileRegistry.class)
    static class PropertiesConfiguration {
    }
}
