package com.mofanyi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.runtime.AppRuntimeDataService;
import com.mofanyi.runtime.AppRuntimeIdentity;
import com.mofanyi.runtime.AppShareService;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.workspace.WorkspaceAclService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1/apps/{appId}")
public class AppRuntimeController {

    private final AppRuntimeDataService dataService;
    private final AppShareService shareService;
    private final AppAuthService appAuthService;
    private final WorkspaceAclService workspaceAclService;

    public AppRuntimeController(AppRuntimeDataService dataService,
                                AppShareService shareService,
                                AppAuthService appAuthService,
                                WorkspaceAclService workspaceAclService) {
        this.dataService = dataService;
        this.shareService = shareService;
        this.appAuthService = appAuthService;
        this.workspaceAclService = workspaceAclService;
    }

    @PostMapping("/sessions")
    public BaseResponse<AppShareService.ExchangedVisitor> exchange(
            @PathVariable Long appId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String token = body == null ? null : body.get("shareToken");
        AppShareService.ExchangedVisitor visitor = shareService.exchange(appId, token);
        Cookie cookie = new Cookie(visitorCookie(appId), visitor.visitorId());
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        // The preview gateway and API have different path prefixes; appId is
        // encoded in the cookie name, so "/" keeps the scoped visitor cookie
        // valid in both same-origin serving modes.
        cookie.setPath("/");
        cookie.setMaxAge((int) Duration.ofDays(30).toSeconds());
        response.addCookie(cookie);
        return ResultUtils.success(visitor);
    }

    @GetMapping("/models/{model}")
    public BaseResponse<List<JsonNode>> list(@PathVariable Long appId,
                                             @PathVariable String model,
                                             @RequestParam(defaultValue = "100") int limit,
                                             HttpServletRequest request) {
        return ResultUtils.success(dataService.list(identity(appId, request), model, limit));
    }

    @GetMapping("/models/{model}/{recordId}")
    public BaseResponse<JsonNode> get(@PathVariable Long appId,
                                      @PathVariable String model,
                                      @PathVariable String recordId,
                                      HttpServletRequest request) {
        return ResultUtils.success(dataService.get(
                identity(appId, request), model, recordId));
    }

    @PostMapping("/models/{model}")
    public BaseResponse<JsonNode> create(@PathVariable Long appId,
                                         @PathVariable String model,
                                         @RequestBody JsonNode body,
                                         HttpServletRequest request) {
        return ResultUtils.success(dataService.create(
                identity(appId, request), model, body));
    }

    @PutMapping("/models/{model}/{recordId}")
    public BaseResponse<JsonNode> update(@PathVariable Long appId,
                                         @PathVariable String model,
                                         @PathVariable String recordId,
                                         @RequestParam(defaultValue = "0") long expectedVersion,
                                         @RequestBody JsonNode body,
                                         HttpServletRequest request) {
        return ResultUtils.success(dataService.update(
                identity(appId, request), model, recordId, expectedVersion, body));
    }

    @DeleteMapping("/models/{model}/{recordId}")
    public BaseResponse<Boolean> delete(@PathVariable Long appId,
                                        @PathVariable String model,
                                        @PathVariable String recordId,
                                        HttpServletRequest request) {
        return ResultUtils.success(dataService.delete(
                identity(appId, request), model, recordId));
    }

    private AppRuntimeIdentity identity(Long appId, HttpServletRequest request) {
        User user = currentUser(request);
        if (user != null) {
            App app = appAuthService.requireCanView(appId, user);
            String role;
            if (user.getId().equals(app.getUserId())) {
                role = "OWNER";
            } else if (app.getWorkspaceId() == null) {
                ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR, "应用未归属工作区");
                return null;
            } else {
                role = switch (workspaceAclService.roleOf(app.getWorkspaceId(), user.getId())) {
                    case OWNER -> "OWNER";
                    case ADMIN -> "ADMIN";
                    case EDITOR -> "MEMBER";
                    case VIEWER -> "VIEWER";
                };
            }
            return new AppRuntimeIdentity(app.getWorkspaceId(), appId,
                    "user:" + user.getId(), role, null, false);
        }
        String visitorId = cookie(request, visitorCookie(appId));
        AppRuntimeIdentity visitor = shareService.visitor(appId, visitorId);
        ThrowUtils.throwIf(visitor == null, ErrorCode.NOT_LOGIN_ERROR,
                "需要登录或有效的应用分享链接");
        return visitor;
    }

    private User currentUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(UserConstant.USER_LOGIN_STATE);
        return value instanceof User user && user.getId() != null ? user : null;
    }

    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String visitorCookie(Long appId) {
        return "xy_visitor_" + appId;
    }
}
