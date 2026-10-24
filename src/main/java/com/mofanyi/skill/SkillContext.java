package com.mofanyi.skill;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillContext {

    private final ConcurrentHashMap<Long, List<SkillDefinition>> active = new ConcurrentHashMap<>();

    public void activate(Long appId, List<SkillDefinition> skills) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || skills == null, ErrorCode.PARAMS_ERROR);
        if (skills.isEmpty()) {
            return;
        }
        ThrowUtils.throwIf(active.putIfAbsent(appId, List.copyOf(skills)) != null,
                ErrorCode.OPERATION_ERROR, "该应用已有活跃技能上下文");
    }

    public List<SkillDefinition> get(Long appId) {
        return appId == null ? List.of() : active.getOrDefault(appId, List.of());
    }

    /**
     * 返回当前技能允许工具的交集。
     *
     * <p>Optional.empty 表示当前没有激活技能，因此不裁剪工具；Optional.of(empty set)
     * 表示技能显式禁止除循环控制工具外的所有能力。
     */
    public Optional<Set<String>> allowedToolIntersection(Long appId) {
        List<SkillDefinition> skills = get(appId);
        if (skills.isEmpty()) {
            return Optional.empty();
        }
        Set<String> intersection = null;
        for (SkillDefinition skill : skills) {
            if (intersection == null) {
                intersection = new LinkedHashSet<>(skill.allowedTools());
            } else {
                intersection.retainAll(skill.allowedTools());
            }
        }
        return Optional.of(Set.copyOf(intersection == null ? Set.of() : intersection));
    }

    public void deactivate(Long appId, List<SkillDefinition> skills) {
        if (appId != null && skills != null && !skills.isEmpty()) {
            active.remove(appId, List.copyOf(skills));
        }
    }
}
