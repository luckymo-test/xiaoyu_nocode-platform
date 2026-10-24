package com.mofanyi.runtime;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受支持 StackProfile 的版本化能力目录。
 */
@Component
public class StackProfileRegistry {

    private final Map<String, StackProfile> profiles;

    public StackProfileRegistry() {
        Map<String, StackProfile> values = new LinkedHashMap<>();
        register(values, new BuiltInProfile(
                "html-static", "1", "原生 HTML", CodeGenTypeEnum.HTML,
                Set.of("static", "responsive", "seo-basic"), true, null,
                List.of(), List.of(), "."));
        register(values, new BuiltInProfile(
                "multi-file-static", "1", "原生多文件", CodeGenTypeEnum.MULTI_FILE,
                Set.of("static", "multi-file", "responsive"), true, null,
                List.of(), List.of(), "."));
        register(values, new BuiltInProfile(
                "vue-vite", "1", "Vue 3 + Vite", CodeGenTypeEnum.VUE,
                Set.of("spa", "routing", "interaction"), true, null,
                List.of(new CommandSpec("install", List.of("npm", "install"), Duration.ofMinutes(5))),
                List.of(new CommandSpec("build", List.of("npm", "run", "build"), Duration.ofMinutes(5))),
                "dist"));
        register(values, new BuiltInProfile(
                "react-vite-ts", "1", "React + Vite + TypeScript", CodeGenTypeEnum.VUE,
                Set.of("spa", "routing", "interaction", "typescript"), false,
                "Prompt/脚手架适配尚未完成",
                List.of(new CommandSpec("install", List.of("npm", "ci"), Duration.ofMinutes(5))),
                List.of(new CommandSpec("build", List.of("npm", "run", "build"), Duration.ofMinutes(5))),
                "dist"));
        register(values, new BuiltInProfile(
                "next-static-export", "1", "Next.js Static Export", CodeGenTypeEnum.VUE,
                Set.of("ssg", "routing", "seo", "motion", "typescript"), false,
                "静态导出脚手架与验证流程尚未完成",
                List.of(new CommandSpec("install", List.of("npm", "ci"), Duration.ofMinutes(8))),
                List.of(new CommandSpec("build", List.of("npm", "run", "build"), Duration.ofMinutes(8))),
                "out"));
        this.profiles = Map.copyOf(values);
    }

    public StackProfile require(String profileId) {
        ThrowUtils.throwIf(profileId == null || profileId.isBlank(), ErrorCode.PARAMS_ERROR);
        StackProfile profile = profiles.get(profileId.toLowerCase(Locale.ROOT));
        ThrowUtils.throwIf(profile == null, ErrorCode.PARAMS_ERROR, "未知 StackProfile");
        ThrowUtils.throwIf(!profile.available(), ErrorCode.PARAMS_ERROR,
                "StackProfile 暂不可用：" + profile.unavailableReason());
        return profile;
    }

    public StackProfile resolve(String requestedId, CodeGenTypeEnum fallbackType) {
        ThrowUtils.throwIf(fallbackType == null, ErrorCode.PARAMS_ERROR);
        if (requestedId == null || requestedId.isBlank() || "auto".equals(requestedId)) {
            return profiles.values().stream()
                    .filter(StackProfile::available)
                    .filter(profile -> profile.compatibilityType() == fallbackType)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("没有可用的 StackProfile"));
        }
        StackProfile profile = require(requestedId);
        ThrowUtils.throwIf(profile.compatibilityType() != fallbackType,
                ErrorCode.PARAMS_ERROR, "StackProfile 与生成类型不兼容");
        return profile;
    }

    public List<StackProfile> list() {
        return List.copyOf(profiles.values());
    }

    public StackProfile get(String id) {
        return id == null ? null : profiles.get(id.toLowerCase(Locale.ROOT));
    }

    private void register(Map<String, StackProfile> target, StackProfile profile) {
        target.put(profile.id(), profile);
    }

    private record BuiltInProfile(
            String id,
            String version,
            String displayName,
            CodeGenTypeEnum compatibilityType,
            Set<String> capabilities,
            boolean available,
            String unavailableReason,
            List<CommandSpec> installCommands,
            List<CommandSpec> buildCommands,
            String outputDirectory) implements StackProfile {
    }
}
