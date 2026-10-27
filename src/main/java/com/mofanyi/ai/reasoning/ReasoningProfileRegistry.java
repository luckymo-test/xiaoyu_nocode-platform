package com.mofanyi.ai.reasoning;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推理档位映射。模型与预算全部来自 application.yml，切换模型不需要改循环代码。
 */
@Component
@ConfigurationProperties(prefix = "codegen.reasoning")
public class ReasoningProfileRegistry {

    private String defaultLevel = "standard";
    private Map<String, ReasoningProfile> profiles = new LinkedHashMap<>();

    public String getDefaultLevel() {
        return defaultLevel;
    }

    public void setDefaultLevel(String defaultLevel) {
        this.defaultLevel = defaultLevel;
    }

    public Map<String, ReasoningProfile> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ReasoningProfile> profiles) {
        this.profiles = profiles == null ? new LinkedHashMap<>() : profiles;
    }

    public ReasoningLevel defaultReasoningLevel() {
        try {
            return ReasoningLevel.from(defaultLevel);
        } catch (IllegalArgumentException e) {
            return ReasoningLevel.STANDARD;
        }
    }

    public ReasoningLevel parseLevel(String requestedLevel) {
        try {
            return ReasoningLevel.from(requestedLevel == null || requestedLevel.isBlank()
                    ? defaultLevel : requestedLevel);
        } catch (IllegalArgumentException e) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, e.getMessage());
            return ReasoningLevel.STANDARD;
        }
    }

    /**
     * 解析请求值并返回有效配置；没有任何配置时使用代码内的安全默认值。
     */
    public ReasoningProfile resolve(String requestedLevel) {
        ReasoningLevel level = parseLevel(requestedLevel);
        ReasoningProfile configured = profiles.get(level.code());
        return configured == null ? fallback(level) : configured;
    }

    public ReasoningProfile resolve(ReasoningLevel level) {
        ThrowUtils.throwIf(level == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");
        ReasoningProfile configured = profiles.get(level.code());
        return configured == null ? fallback(level) : configured;
    }

    private ReasoningProfile fallback(ReasoningLevel level) {
        return switch (level) {
            case FAST -> new ReasoningProfile(false, null, 16, 1_000_000L, 1,
                    32_768, "deepseek-flash", 600);
            case ECO -> new ReasoningProfile(true, "low", 24, 2_000_000L, 1,
                    32_768, "deepseek-flash", 900);
            case STANDARD -> new ReasoningProfile(true, "high", 48, 4_000_000L, 2,
                    65_536, "deepseek-flash", 1_200);
            case MAX -> new ReasoningProfile(true, "max", 64, 6_000_000L, 3,
                    65_536, "deepseek-flash", 1_500);
        };
    }
}
