package com.mofanyi.skill;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.List;
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

    public void deactivate(Long appId, List<SkillDefinition> skills) {
        if (appId != null && skills != null && !skills.isEmpty()) {
            active.remove(appId, List.copyOf(skills));
        }
    }
}
