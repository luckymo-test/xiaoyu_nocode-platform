package com.mofanyi.workspace;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Job 执行期间的草稿目录映射。外部读取可通过 {@link #withLive(Supplier)} 显式绕过。
 */
@Component
public class ProjectWorkspaceContext {

    private final ConcurrentHashMap<Long, Path> overrides = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> forceLive = ThreadLocal.withInitial(() -> false);

    public void activate(Long appId, Path workspace) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(workspace == null, ErrorCode.PARAMS_ERROR);
        Path normalized = workspace.toAbsolutePath().normalize();
        Path previous = overrides.putIfAbsent(appId, normalized);
        ThrowUtils.throwIf(previous != null, ErrorCode.OPERATION_ERROR,
                "该应用已有运行中的隔离工作区");
    }

    public void deactivate(Long appId, Path workspace) {
        if (appId == null || workspace == null) {
            return;
        }
        overrides.remove(appId, workspace.toAbsolutePath().normalize());
    }

    public Path resolve(Long appId) {
        if (appId == null || Boolean.TRUE.equals(forceLive.get())) {
            return null;
        }
        return overrides.get(appId);
    }

    public <T> T withLive(Supplier<T> action) {
        ThrowUtils.throwIf(action == null, ErrorCode.PARAMS_ERROR);
        boolean previous = Boolean.TRUE.equals(forceLive.get());
        forceLive.set(true);
        try {
            return action.get();
        } finally {
            if (previous) {
                forceLive.set(true);
            } else {
                forceLive.remove();
            }
        }
    }
}
