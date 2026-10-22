package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.vo.AppRevisionVO;
import com.mofanyi.model.vo.AppRevisionDiffVO;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.service.AppRevisionService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用版本查询、发布与回滚。
 */
@RestController
@RequestMapping("/v1/apps/{appId}/revisions")
public class AppRevisionController {

    private final AppRevisionService revisionService;
    private final AppAuthService appAuthService;
    private final UserService userService;

    public AppRevisionController(AppRevisionService revisionService,
                                 AppAuthService appAuthService,
                                 UserService userService) {
        this.revisionService = revisionService;
        this.appAuthService = appAuthService;
        this.userService = userService;
    }

    @GetMapping
    public BaseResponse<List<AppRevisionVO>> list(@PathVariable Long appId,
                                                  HttpServletRequest request) {
        requireOwner(appId, request);
        return ResultUtils.success(revisionService.list(appId, false));
    }

    @GetMapping("/{revisionId}")
    public BaseResponse<AppRevisionVO> get(@PathVariable Long appId,
                                           @PathVariable Long revisionId,
                                           HttpServletRequest request) {
        requireOwner(appId, request);
        return ResultUtils.success(revisionService.get(appId, revisionId));
    }

    @PostMapping("/{revisionId}/publish")
    public BaseResponse<AppRevisionVO> publish(@PathVariable Long appId,
                                               @PathVariable Long revisionId,
                                               HttpServletRequest request) {
        User loginUser = requireOwner(appId, request);
        return ResultUtils.success(revisionService.publishExisting(
                appId, revisionId, loginUser.getId()));
    }

    @GetMapping("/compare")
    public BaseResponse<AppRevisionDiffVO> compare(@PathVariable Long appId,
                                                   @RequestParam Long from,
                                                   @RequestParam Long to,
                                                   HttpServletRequest request) {
        requireOwner(appId, request);
        return ResultUtils.success(revisionService.compare(appId, from, to));
    }

    @PostMapping("/{revisionId}/rollback")
    public BaseResponse<AppRevisionVO> rollback(@PathVariable Long appId,
                                                @PathVariable Long revisionId,
                                                HttpServletRequest request) {
        User loginUser = requireOwner(appId, request);
        return ResultUtils.success(revisionService.publishExisting(
                appId, revisionId, loginUser.getId()));
    }

    private User requireOwner(Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        appAuthService.requireOwner(appId, loginUser);
        return loginUser;
    }
}
