package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.config.SandboxProperties;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.runtime.StackProfile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Build Workspace 的依赖 allowlist、文件配额、符号链接和临时目录守卫。
 */
@Component
public class SandboxWorkspaceGuard {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "dist", "out", ".next", "build", "target", ".agent");
    private static final Set<String> ALLOWED_BUILD_SCRIPTS = Set.of(
            "vite build",
            "vue-tsc --noEmit && vite build",
            "vue-tsc -b && vite build");

    private final SandboxProperties properties;
    private final ObjectMapper objectMapper;

    public SandboxWorkspaceGuard(SandboxProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Stats validateSource(Path source) {
        validateSource(source, null);
        return stats(source, properties.getMaxSourceFiles(), properties.getMaxSourceBytes(), true);
    }

    /**
     * 按当前 StackProfile 校验依赖和 build 脚本。
     */
    public Stats validateSource(Path source, StackProfile profile) {
        validatePackage(source, profile);
        return stats(source, properties.getMaxSourceFiles(), properties.getMaxSourceBytes(), true);
    }

    public Path createBuildWorkspace(String tmpRoot, Long appId) {
        try {
            Path root = Path.of(tmpRoot, "sandbox", "builds",
                    appId + "-" + UUID.randomUUID()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "创建构建沙箱失败：" + e.getMessage());
        }
    }

    public void copySource(Path source, Path target) {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty() || shouldSkip(relative)) {
                    return;
                }
                Path destination = target.resolve(relative);
                try {
                    if (Files.isSymbolicLink(path)) {
                        throw new BusinessException(ErrorCode.OPERATION_ERROR,
                                "工程包含不允许的符号链接：" + relative);
                    }
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else if (Files.isRegularFile(path)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("复制到构建沙箱失败: " + relative, e);
                }
            });
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "扫描构建源文件失败：" + e.getMessage());
        }
    }

    /**
     * 修复 Agent 改的是 live 草稿；重新构建前同步源码，但保留已安装的 node_modules。
     */
    public void syncSource(Path source, Path target) {
        try (Stream<Path> children = Files.list(target)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!Set.of("node_modules", ".npm-cache", ".tmp").contains(name)) {
                    FileUtil.del(child.toFile());
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "清理构建沙箱源码失败：" + e.getMessage());
        }
        copySource(source, target);
    }

    public void replaceOutput(Path builtDist, Path liveDist) {
        FileUtil.del(liveDist.toFile());
        copySource(builtDist, liveDist);
    }

    public Stats validateOutput(Path output) {
        if (!Files.isDirectory(output)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "构建未产出 dist 目录");
        }
        return stats(output, properties.getMaxOutputFiles(),
                properties.getMaxOutputBytes(), false);
    }

    public void cleanup(Path sandbox) {
        if (sandbox != null) {
            FileUtil.del(sandbox.toFile());
        }
    }

    private void validatePackage(Path source, StackProfile profile) {
        Path packageJson = source.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少 package.json");
        }
        if (properties.getBuild().isRequireLockfile()
                && !Files.isRegularFile(source.resolve("package-lock.json"))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "沙箱模式要求 package-lock.json，拒绝未锁定依赖");
        }
        try {
            JsonNode root = objectMapper.readTree(packageJson.toFile());
            Set<String> allowed = profile == null || profile.allowedDependencies().isEmpty()
                    ? new HashSet<>(properties.getBuild().getAllowedPackages())
                    : new HashSet<>(profile.allowedDependencies());
            validateDependencies(root.path("dependencies"), allowed, "dependencies");
            validateDependencies(root.path("devDependencies"), allowed, "devDependencies");
            String buildScript = root.path("scripts").path("build").asText("").trim()
                    .replaceAll("\\s+", " ");
            Set<String> allowedScripts = profile == null || profile.allowedBuildScripts().isEmpty()
                    ? ALLOWED_BUILD_SCRIPTS : profile.allowedBuildScripts();
            if (!allowedScripts.contains(buildScript)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "package.json build 脚本不在沙箱 allowlist：" + buildScript);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "package.json 非法：" + e.getMessage());
        }
    }

    private static void validateDependencies(JsonNode dependencies, Set<String> allowed,
                                             String section) {
        if (dependencies == null || dependencies.isMissingNode()) {
            return;
        }
        if (!dependencies.isObject()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, section + " 必须是对象");
        }
        Iterator<String> names = dependencies.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "依赖不在沙箱 allowlist：" + section + "." + name);
            }
        }
    }

    private static Stats stats(Path root, int maxFiles, long maxBytes, boolean excludeHeavy) {
        int files = 0;
        long bytes = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path relative = root.relativize(path);
                if (relative.toString().isEmpty()
                        || (excludeHeavy && shouldSkip(relative))) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "工作区包含不允许的符号链接：" + relative);
                }
                if (Files.isRegularFile(path)) {
                    files++;
                    bytes += Files.size(path);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "统计工作区文件失败：" + e.getMessage());
        }
        if (files > maxFiles) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "工作区文件数超限：" + files + "/" + maxFiles);
        }
        if (bytes > maxBytes) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "工作区大小超限：" + bytes + "/" + maxBytes);
        }
        return new Stats(files, bytes);
    }

    private static boolean shouldSkip(Path relative) {
        for (Path part : relative) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    public record Stats(int files, long bytes) {
    }
}
