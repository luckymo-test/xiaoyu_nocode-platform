package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.model.vo.GenerationJobVO;
import com.mofanyi.entity.AppDeployment;
import com.mofanyi.mapper.AppDeploymentMapper;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;
import com.mybatisflex.core.query.QueryWrapper;

@RestController
@RequestMapping("/v1/apps/{appId}/deployments")
public class AppDeploymentController {

    private final GenerationJobService jobs;
    private final UserService users;
    private final AppDeploymentMapper deployments;
    private final AppAuthService appAuthService;

    public AppDeploymentController(GenerationJobService jobs, UserService users,
                                   AppDeploymentMapper deployments,
                                   AppAuthService appAuthService) {
        this.jobs = jobs;
        this.users = users;
        this.deployments = deployments;
        this.appAuthService = appAuthService;
    }

    @PostMapping
    public BaseResponse<GenerationJobVO> create(
            @PathVariable Long appId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request) {
        String idempotencyKey = key == null || key.isBlank()
                ? "deploy-" + UUID.randomUUID() : key;
        return ResultUtils.success(jobs.createDeploy(
                appId, idempotencyKey, users.getLoginUser(request)));
    }

    @GetMapping
    public BaseResponse<List<AppDeployment>> list(
            @PathVariable Long appId, HttpServletRequest request) {
        var user = users.getLoginUser(request);
        appAuthService.requireCanView(appId, user);
        return ResultUtils.success(deployments.selectListByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", false)
                .limit(100)));
    }
}
