package com.mofanyi.skill;

import java.util.List;
import java.util.Set;

/**
 * 技能是能力、工具、约束和验收规则的组合，不等同于一段 Prompt。
 */
public record SkillDefinition(
        String id,
        String version,
        String displayName,
        Set<String> compatibleProfiles,
        Set<String> allowedTools,
        Set<String> permissions,
        String promptFragment,
        List<String> validationRules,
        Set<String> conflictsWith) {

    public SkillDefinition {
        compatibleProfiles = compatibleProfiles == null ? Set.of() : Set.copyOf(compatibleProfiles);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        validationRules = validationRules == null ? List.of() : List.copyOf(validationRules);
        conflictsWith = conflictsWith == null ? Set.of() : Set.copyOf(conflictsWith);
    }
}
