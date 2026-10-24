package com.mofanyi.core;

import com.mofanyi.config.SandboxProperties;
import com.mofanyi.job.GenerationExecutionCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 生产沙箱启动门禁。
 *
 * <p>这是防止配置漂移的 fail-closed 检查，不等价于容器运行时证明。真正的内核隔离
 * 仍由 cgroup、namespace、只读 rootfs 和容器网络配置提供。
 */
@Slf4j
@Component
@Conditional(GenerationExecutionCondition.class)
public class SandboxIsolationVerifier implements InitializingBean {

    private static final Pattern CONTAINER_CGROUP = Pattern.compile(
            "docker|kubepods|libpod|containerd|podman|garden", Pattern.CASE_INSENSITIVE);

    private final SandboxProperties properties;

    public SandboxIsolationVerifier(SandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isRequireContainer()) {
            return;
        }
        RuntimeSignals signals = currentRuntime();
        String rejection = rejectionReason(signals);
        if (rejection != null) {
            throw new IllegalStateException("Sandbox 生产隔离启动失败：" + rejection);
        }
        log.info("[sandbox] 容器隔离门禁通过，user={}, marker={}",
                signals.user(), signals.containerMarker());
    }

    static RuntimeSignals currentRuntime() {
        boolean dockerMarker = Files.exists(Path.of("/.dockerenv"));
        boolean podmanMarker = Files.exists(Path.of("/run/.containerenv"));
        String cgroup = readCgroup();
        String containerEnv = System.getenv("container");
        return new RuntimeSignals(
                dockerMarker,
                podmanMarker,
                containerEnv,
                cgroup,
                System.getProperty("user.name", ""));
    }

    static String rejectionReason(RuntimeSignals signals) {
        if (signals == null) {
            return "无法读取运行时信号";
        }
        if (!signals.containerMarker()) {
            return "未检测到 Docker/Podman/Kubernetes/containerd 容器标记";
        }
        if (signals.user() == null || signals.user().isBlank()) {
            return "无法确认运行用户";
        }
        if ("root".equalsIgnoreCase(signals.user().trim())) {
            return "Browser/Build 进程不得以 root 运行";
        }
        return null;
    }

    private static String readCgroup() {
        try {
            return Files.readString(Path.of("/proc/1/cgroup"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    record RuntimeSignals(boolean dockerMarker, boolean podmanMarker,
                          String containerEnv, String cgroup, String user) {

        boolean containerMarker() {
            String env = containerEnv == null ? "" : containerEnv.toLowerCase(Locale.ROOT);
            return dockerMarker
                    || podmanMarker
                    || env.equals("docker")
                    || env.equals("podman")
                    || env.equals("containerd")
                    || CONTAINER_CGROUP.matcher(cgroup == null ? "" : cgroup).find();
        }
    }
}
