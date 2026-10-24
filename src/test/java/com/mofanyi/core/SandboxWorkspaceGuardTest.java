package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.config.SandboxProperties;
import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxWorkspaceGuardTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("依赖与 build 脚本必须在 allowlist 内且要求 lockfile")
    void validatesPackagePolicy() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        SandboxWorkspaceGuard guard = new SandboxWorkspaceGuard(
                properties, new ObjectMapper());
        writeProject("""
                {
                  "scripts":{"build":"vite build"},
                  "dependencies":{"vue":"^3.4.0"},
                  "devDependencies":{"vite":"^5.0.0","@vitejs/plugin-vue":"^5.0.0"}
                }
                """);

        assertDoesNotThrow(() -> guard.validateSource(tempDir));
    }

    @Test
    @DisplayName("未知依赖和任意 build 命令被拒绝")
    void rejectsUnknownDependencyAndScript() throws Exception {
        SandboxWorkspaceGuard guard = new SandboxWorkspaceGuard(
                new SandboxProperties(), new ObjectMapper());
        writeProject("""
                {
                  "scripts":{"build":"curl http://169.254.169.254 && sh -c whoami"},
                  "dependencies":{"unknown-package":"1.0.0"}
                }
                """);

        assertThrows(BusinessException.class, () -> guard.validateSource(tempDir));
    }

    @Test
    @DisplayName("缺少 lockfile 或文件配额超限时失败")
    void enforcesLockfileAndFileQuota() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        SandboxWorkspaceGuard guard = new SandboxWorkspaceGuard(properties, new ObjectMapper());
        writeProject("""
                {"scripts":{"build":"vite build"},"dependencies":{"vue":"^3.4.0"}}
                """);
        Files.delete(tempDir.resolve("package-lock.json"));
        assertThrows(BusinessException.class, () -> guard.validateSource(tempDir));

        Files.writeString(tempDir.resolve("package-lock.json"), "{}");
        properties.setMaxSourceFiles(1);
        assertThrows(BusinessException.class, () -> guard.validateSource(tempDir));
    }

    private void writeProject(String packageJson) throws Exception {
        Files.writeString(tempDir.resolve("package.json"), packageJson);
        Files.writeString(tempDir.resolve("package-lock.json"), "{}");
    }
}
