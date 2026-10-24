package com.mofanyi.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * npm/Vite 子进程的环境变量白名单。
 *
 * <p>生成工程中的配置文件和依赖同样能读取进程环境，因此只剔除常见 Secret 前缀
 * 不足以避免自定义密钥泄漏。这里采用 fail-closed 白名单，仅保留 Node/npm 启动
 * 和跨平台路径解析需要的最小系统变量。
 */
public final class SandboxEnvironment {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR",
            "OS", "PROCESSOR_ARCHITECTURE", "NUMBER_OF_PROCESSORS",
            "LANG", "LANGUAGE", "LC_ALL", "TZ");

    private SandboxEnvironment() {
    }

    public static void retainSafeKeys(Map<String, String> environment) {
        environment.keySet().removeIf(key -> !isAllowed(key));
    }

    static boolean isAllowed(String key) {
        return key != null && ALLOWED_KEYS.contains(key.toUpperCase(Locale.ROOT));
    }
}
