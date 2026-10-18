package com.mofanyi.ai.reasoning;

import java.util.Locale;

/**
 * 用户可选的四档推理强度。别名只用于兼容旧客户端/探针，UI 只暴露 code 值。
 */
public enum ReasoningLevel {
    FAST("fast"),
    ECO("eco"),
    STANDARD("standard"),
    MAX("max");

    private final String code;

    ReasoningLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 把请求值归一化为有效档位。空白使用默认标准档；非法值由调用方拒绝。
     */
    public static ReasoningLevel from(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "fast", "off", "disabled" -> FAST;
            case "eco", "minimal", "low" -> ECO;
            case "standard", "medium", "high", "xhigh" -> STANDARD;
            case "max", "ultra" -> MAX;
            default -> throw new IllegalArgumentException("不支持的推理档位：" + value);
        };
    }
}
