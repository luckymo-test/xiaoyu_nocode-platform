package com.mofanyi.template;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个 Job 期间锁定模板；同 app 只允许一个活跃 Job，因此按 appId 索引安全。
 */
@Component
public class TemplateContext {

    private final ConcurrentHashMap<Long, TemplateBundle> active = new ConcurrentHashMap<>();

    public void activate(Long appId, TemplateBundle bundle) {
        ThrowUtils.throwIf(appId == null || appId <= 0 || bundle == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(active.putIfAbsent(appId, bundle) != null,
                ErrorCode.OPERATION_ERROR, "该应用已有活跃模板上下文");
    }

    public TemplateBundle get(Long appId) {
        return appId == null ? null : active.get(appId);
    }

    public void deactivate(Long appId, TemplateBundle bundle) {
        if (appId != null && bundle != null) {
            active.remove(appId, bundle);
        }
    }
}
