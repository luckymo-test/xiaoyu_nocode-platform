package com.mofanyi.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxEnvironmentTest {

    @Test
    @DisplayName("npm 环境使用白名单并剔除任意自定义密钥")
    void retainsOnlyPlatformVariables() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("LANG", "zh_CN.UTF-8");
        environment.put("CUSTOM_SECRET", "must-not-leak");
        environment.put("OPENAI_API_KEY", "must-not-leak");
        environment.put("HTTP_PROXY", "http://user:password@proxy");
        environment.put("NODE_OPTIONS", "--require=/tmp/evil.js");

        SandboxEnvironment.retainSafeKeys(environment);

        assertEquals(Map.of("PATH", "/usr/bin", "LANG", "zh_CN.UTF-8"), environment);
    }
}
