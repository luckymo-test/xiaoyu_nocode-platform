package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.service.UserService;
import com.mofanyi.service.UserUsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/v1/users/me/usage")
public class UserUsageController {

    private final UserUsageService usageService;
    private final UserService userService;

    public UserUsageController(UserUsageService usageService, UserService userService) {
        this.usageService = usageService;
        this.userService = userService;
    }

    @GetMapping
    public BaseResponse<UserUsageService.UsageSummary> summary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        return ResultUtils.success(usageService.summary(
                userService.getLoginUser(request), from, to));
    }
}
