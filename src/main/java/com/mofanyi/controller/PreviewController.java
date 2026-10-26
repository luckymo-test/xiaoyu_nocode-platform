package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.service.PreviewTokenService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/apps/{appId}/preview-token")
public class PreviewController {

    private final PreviewTokenService tokens;
    private final UserService users;

    public PreviewController(PreviewTokenService tokens, UserService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @GetMapping
    public BaseResponse<PreviewTokenService.PreviewAccess> issue(
            @PathVariable Long appId, HttpServletRequest request) {
        return ResultUtils.success(tokens.issue(
                appId, users.getLoginUser(request)));
    }
}
