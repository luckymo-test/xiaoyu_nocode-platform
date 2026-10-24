package com.mofanyi.runtime;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个 Job 期间激活的 StackProfile。
 *
 * <p>Job 对同一 App 独占，因此按 appId 索引不会并发覆盖。生成、构建和构建修复都从
 * 同一上下文读取 Profile，避免在 Facade 内继续增加 CodeGenType switch。
 */
@Component
public class StackProfileContext {

    private final StackProfileRegistry profiles;
    private final ConcurrentHashMap<Long, StackProfile> active = new ConcurrentHashMap<>();

    public StackProfileContext(StackProfileRegistry profiles) {
        this.profiles = profiles;
    }

    public void activate(Long appId, StackProfile profile) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || profile == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(active.putIfAbsent(appId, profile) != null,
                ErrorCode.OPERATION_ERROR, "该应用已有活跃 StackProfile");
    }

    public StackProfile resolve(Long appId, CodeGenTypeEnum type) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || type == null,
                ErrorCode.PARAMS_ERROR);
        StackProfile current = active.get(appId);
        if (current != null) {
            ThrowUtils.throwIf(current.compatibilityType() != type,
                    ErrorCode.OPERATION_ERROR, "活跃 StackProfile 与生成类型不兼容");
            return current;
        }
        // 兼容旧非 Job 调用：Vue 类型继续落到默认 vue-vite。
        return profiles.resolve("auto", type);
    }

    public StackProfile get(Long appId) {
        return appId == null ? null : active.get(appId);
    }

    public void deactivate(Long appId, StackProfile profile) {
        if (appId != null && profile != null) {
            active.remove(appId, profile);
        }
    }
}
