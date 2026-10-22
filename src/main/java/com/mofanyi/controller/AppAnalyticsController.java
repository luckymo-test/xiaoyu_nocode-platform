package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.analytics.AnalyticsEventRequest;
import com.mofanyi.model.vo.AnalyticsDailyVO;
import com.mofanyi.service.AppAnalyticsService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/v1")
public class AppAnalyticsController {

    private final AppAnalyticsService analyticsService;
    private final UserService userService;

    public AppAnalyticsController(AppAnalyticsService analyticsService,
                                  UserService userService) {
        this.analyticsService = analyticsService;
        this.userService = userService;
    }

    @PostMapping("/analytics/events")
    public BaseResponse<Boolean> record(@RequestBody AnalyticsEventRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(analyticsService.record(request));
    }

    @GetMapping("/apps/{appId}/analytics/daily")
    public BaseResponse<List<AnalyticsDailyVO>> daily(
            @org.springframework.web.bind.annotation.PathVariable Long appId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(analyticsService.daily(
                appId, from, to, loginUser.getId()));
    }
}
