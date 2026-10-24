package com.mofanyi.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SandboxIsolationVerifierTest {

    @Test
    @DisplayName("容器标记与非 root 用户通过启动门禁")
    void acceptsNonRootContainer() {
        SandboxIsolationVerifier.RuntimeSignals signals =
                new SandboxIsolationVerifier.RuntimeSignals(
                        true, false, "", "", "app");

        assertNull(SandboxIsolationVerifier.rejectionReason(signals));
    }

    @Test
    @DisplayName("普通进程和 root 容器均被拒绝")
    void rejectsProcessAndRoot() {
        SandboxIsolationVerifier.RuntimeSignals process =
                new SandboxIsolationVerifier.RuntimeSignals(
                        false, false, "", "0::/user.slice", "app");
        SandboxIsolationVerifier.RuntimeSignals root =
                new SandboxIsolationVerifier.RuntimeSignals(
                        true, false, "docker", "", "root");

        assertNotNull(SandboxIsolationVerifier.rejectionReason(process));
        assertNotNull(SandboxIsolationVerifier.rejectionReason(root));
    }

    @Test
    @DisplayName("cgroup 与 Podman 标记可作为容器信号")
    void acceptsCgroupAndPodmanMarkers() {
        SandboxIsolationVerifier.RuntimeSignals cgroup =
                new SandboxIsolationVerifier.RuntimeSignals(
                        false, false, "", "0::/kubepods.slice/pod123", "app");
        SandboxIsolationVerifier.RuntimeSignals podman =
                new SandboxIsolationVerifier.RuntimeSignals(
                        false, true, "podman", "", "app");

        assertNull(SandboxIsolationVerifier.rejectionReason(cgroup));
        assertNull(SandboxIsolationVerifier.rejectionReason(podman));
    }
}
