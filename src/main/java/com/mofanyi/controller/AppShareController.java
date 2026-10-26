package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.runtime.AppShareService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/apps/{appId}/shares")
public class AppShareController {

    public record CreateShareRequest(Integer expiresHours, List<String> models) {
    }

    private final AppShareService shareService;
    private final UserService userService;

    public AppShareController(AppShareService shareService, UserService userService) {
        this.shareService = shareService;
        this.userService = userService;
    }

    @PostMapping
    public BaseResponse<AppShareService.CreatedShare> create(
            @PathVariable Long appId,
            @RequestBody(required = false) CreateShareRequest body,
            HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getLoginUser(request);
        return ResultUtils.success(shareService.create(
                appId, user,
                body == null ? null : body.expiresHours(),
                body == null ? List.of() : body.models()));
    }

    @DeleteMapping("/{shareId}")
    public BaseResponse<Boolean> revoke(@PathVariable Long appId,
                                        @PathVariable Long shareId,
                                        HttpServletRequest request) {
        shareService.revoke(appId, shareId, userService.getLoginUser(request));
        return ResultUtils.success(true);
    }
}
