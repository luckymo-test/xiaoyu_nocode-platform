package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/security")
public class SecurityController {

    @GetMapping("/csrf")
    public BaseResponse<Map<String, Boolean>> csrf(HttpServletRequest request) {
        Object value = request.getAttribute(CsrfToken.class.getName());
        if (value instanceof CsrfToken token) {
            // 读取 token 会触发 CookieCsrfTokenRepository 写入 XSRF-TOKEN。
            token.getToken();
            return ResultUtils.success(Map.of("enabled", true));
        }
        return ResultUtils.success(Map.of("enabled", false));
    }
}
