package com.mofanyi.template;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class TemplateRegistry {

    private final Map<String, TemplateBundle> templates = new LinkedHashMap<>();

    public TemplateRegistry() {
        register(new TemplateBundle(
                "default-neutral", "1", "中性基础模板",
                java.util.Set.of("html-static", "multi-file-static", "vue-vite"),
                List.of("hero", "content", "cta"),
                Map.of("color.primary", "#111827", "radius.md", "16px", "space.scale", "4/8/16/24/48"),
                "保持清晰的视觉层级、克制配色和一致间距，优先保证内容可读性与移动端布局。",
                null));
        register(new TemplateBundle(
                "photography-editorial", "1", "摄影杂志画廊",
                java.util.Set.of("html-static", "multi-file-static", "vue-vite"),
                List.of("cinematic-hero", "masonry-gallery", "story-strip", "photographer-bio", "contact-cta"),
                Map.of("color.background", "#0d0d0f", "color.foreground", "#f5f2ea",
                        "color.accent", "#c6a869", "type.display", "serif", "radius.md", "4px"),
                "使用杂志编辑式构图：强标题、留白、非均匀图片节奏；画廊卡片不得把人像裁成不可读的细长条，"
                        + "纵向照片优先使用 portrait 比例和 object-fit: cover。",
                null));
    }

    public TemplateBundle resolve(String hint, String userMessage, String profileId) {
        ThrowUtils.throwIf(profileId == null || profileId.isBlank(), ErrorCode.PARAMS_ERROR);
        String requested = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
        if (requested.isBlank() || "auto".equals(requested)) {
            String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
            requested = text.contains("摄影") || text.contains("照片墙")
                    || text.contains("photography") || text.contains("gallery")
                    ? "photography-editorial" : "default-neutral";
        }
        TemplateBundle bundle = templates.get(requested);
        ThrowUtils.throwIf(bundle == null, ErrorCode.PARAMS_ERROR, "未知模板");
        ThrowUtils.throwIf(!bundle.compatibleProfiles().contains(profileId),
                ErrorCode.PARAMS_ERROR, "模板与 StackProfile 不兼容");
        return bundle;
    }

    public TemplateBundle get(String id) {
        return id == null ? null : templates.get(id);
    }

    public List<TemplateBundle> list() {
        return List.copyOf(templates.values());
    }

    private void register(TemplateBundle source) {
        TemplateBundle withHash = new TemplateBundle(source.id(), source.version(),
                source.displayName(), source.compatibleProfiles(), source.sectionIds(),
                source.designTokens(), source.promptFragment(), hash(source));
        templates.put(withHash.id(), withHash);
    }

    private String hash(TemplateBundle bundle) {
        try {
            String content = bundle.id() + "|" + bundle.version() + "|"
                    + String.join(",", bundle.compatibleProfiles().stream().sorted().toList()) + "|"
                    + String.join(",", bundle.sectionIds()) + "|" + bundle.designTokens() + "|"
                    + bundle.promptFragment();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算模板摘要失败", e);
        }
    }
}
