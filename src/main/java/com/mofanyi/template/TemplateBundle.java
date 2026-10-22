package com.mofanyi.template;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可组合、可版本化的视觉与结构模板包。
 */
public record TemplateBundle(
        String id,
        String version,
        String displayName,
        Set<String> compatibleProfiles,
        List<String> sectionIds,
        Map<String, String> designTokens,
        String promptFragment,
        String effectiveHash) {

    public TemplateBundle {
        compatibleProfiles = compatibleProfiles == null ? Set.of() : Set.copyOf(compatibleProfiles);
        sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
        designTokens = designTokens == null ? Map.of() : Map.copyOf(designTokens);
    }
}
