package com.mofanyi.service;

import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import org.springframework.stereotype.Component;

/**
 * 应用归属校验（plan-refactor 6.1）：把"只能操作自己的应用"这条规则收敛到一处。
 *
 * <p>此前同一条规则散落在 3 个地方（AiChatController ×4、ManualEditController、AppServiceImpl），
 * 文案都不一样，漏改一处就是越权。
 *
 * <p>直接依赖 {@link AppMapper} 而非 AppService，避免与 AppServiceImpl 形成循环依赖
 * （与 ChatHistoryServiceImpl 取归属的做法一致）。
 */
@Component
public class AppAuthService {

    private final AppMapper appMapper;

    public AppAuthService(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    /**
     * 查库并校验登录用户是该应用的创建者。
     *
     * @param appId     应用 id
     * @param loginUser 登录用户
     * @return 应用实体（已确认归属）
     */
    public App requireOwner(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = appMapper.selectOneById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        requireOwner(app, loginUser);
        return app;
    }

    /**
     * 校验已加载的应用是否属于登录用户（调用方已持有实体时使用）。
     *
     * @param app       应用实体
     * @param loginUser 登录用户
     */
    public void requireOwner(App app, User loginUser) {
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
    }
}
