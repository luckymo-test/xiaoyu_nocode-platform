package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.vo.AppRevisionVO;
import com.mofanyi.model.vo.AppRevisionDiffVO;
import com.mofanyi.model.vo.GenerationJobVO;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.service.AppRevisionService;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.UUID;

/**
 * 应用版本查询、发布与回滚。
 */
@RestController
@RequestMapping("/v1/apps/{appId}/revisions")
public class AppRevisionController {

    private final AppRevisionService revisionService;
    private final GenerationJobService jobService;
    private final AppAuthService appAuthService;
    private final UserService userService;

    public AppRevisionController(AppRevisionService revisionService,
                                 GenerationJobService jobService,
                                 AppAuthService appAuthService,
                                 UserService userService) {
        this.revisionService = revisionService;
        this.jobService = jobService;
        this.appAuthService = appAuthService;
        this.userService = userService;
    }

    @GetMapping
    public BaseResponse<List<AppRevisionVO>> list(@PathVariable Long appId,
                                                  HttpServletRequest request) {
        requirePermission(appId, request, false);
        return ResultUtils.success(revisionService.list(appId, false));
    }

    @GetMapping("/{revisionId}")
    public BaseResponse<AppRevisionVO> get(@PathVariable Long appId,
                                           @PathVariable Long revisionId,
                                           HttpServletRequest request) {
        requirePermission(appId, request, false);
        return ResultUtils.success(revisionService.get(appId, revisionId));
    }

    @PostMapping("/{revisionId}/publish")
    public BaseResponse<GenerationJobVO> publish(@PathVariable Long appId,
                                                 @PathVariable Long revisionId,
                                                 @RequestHeader(value = "Idempotency-Key",
                                                         required = false) String idempotencyKey,
                                                 HttpServletRequest request) {
        User loginUser = requirePermission(appId, request, true);
        return ResultUtils.success(jobService.createRevisionPublish(
                appId, revisionId,
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? "publish-" + UUID.randomUUID() : idempotencyKey,
                loginUser));
    }

    @GetMapping("/compare")
    public BaseResponse<AppRevisionDiffVO> compare(@PathVariable Long appId,
                                                   @RequestParam Long from,
                                                   @RequestParam Long to,
                                                   HttpServletRequest request) {
        requirePermission(appId, request, false);
        return ResultUtils.success(revisionService.compare(appId, from, to));
    }

    @PostMapping("/{revisionId}/rollback")
    public BaseResponse<GenerationJobVO> rollback(@PathVariable Long appId,
                                                  @PathVariable Long revisionId,
                                                  @RequestHeader(value = "Idempotency-Key",
                                                          required = false) String idempotencyKey,
                                                  HttpServletRequest request) {
        User loginUser = requirePermission(appId, request, true);
        return ResultUtils.success(jobService.createRevisionPublish(
                appId, revisionId,
                idempotencyKey == null || idempotencyKey.isBlank()
                        ? "rollback-" + UUID.randomUUID() : idempotencyKey,
                loginUser));
    }

    private User requirePermission(Long appId, HttpServletRequest request, boolean publish) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        if (publish) {
            appAuthService.requireCanPublish(appId, loginUser);
        } else {
            appAuthService.requireCanView(appId, loginUser);
        }
        return loginUser;
    }
}
