package com.mofanyi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Build/Browser 执行沙箱的应用级配额与 allowlist。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "codegen.sandbox")
public class SandboxProperties {

    private boolean enabled = true;
    /**
     * 生产 Worker 可设为 true：检测不到容器标记或仍以 root 运行时拒绝启动。
     */
    private boolean requireContainer = false;
    private int maxSourceFiles = 2_000;
    private long maxSourceBytes = 100L * 1024 * 1024;
    private int maxOutputFiles = 10_000;
    private long maxOutputBytes = 200L * 1024 * 1024;
    private long commandTimeoutSeconds = 300;
    private Build build = new Build();
    private Egress egress = new Egress();

    @Getter
    @Setter
    public static class Build {
        private boolean requireLockfile = true;
        private boolean ignoreScripts = true;
        private String registry = "https://registry.npmmirror.com";
        private List<String> allowedPackages = new ArrayList<>(List.of(
                "vue", "vite", "@vitejs/plugin-vue", "typescript", "vue-tsc",
                "pinia", "vue-router", "axios", "gsap"));
    }

    @Getter
    @Setter
    public static class Egress {
        private List<String> allowedHosts = new ArrayList<>(List.of(
                "127.0.0.1", "localhost",
                "yun-picture-1419359718.cos.ap-shanghai.myqcloud.com",
                "images.pexels.com", "cdn.pixabay.com",
                "fonts.googleapis.com", "fonts.gstatic.com"));
    }
}
