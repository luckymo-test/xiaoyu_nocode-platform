package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.service.ProjectTemplateService;
import com.mofanyi.service.UserService;
import com.mofanyi.template.ProjectTemplateDefinition;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProjectTemplateController {

    public record CreateFromTemplateRequest(String templateId, String appName) {
    }

    private final ProjectTemplateService templateService;
    private final UserService userService;

    public ProjectTemplateController(ProjectTemplateService templateService,
                                     UserService userService) {
        this.templateService = templateService;
        this.userService = userService;
    }

    @GetMapping("/v1/project-templates")
    public BaseResponse<List<ProjectTemplateDefinition>> list() {
        return ResultUtils.success(templateService.list());
    }

    @PostMapping("/v1/apps/from-template")
    public BaseResponse<ProjectTemplateService.CreatedProject> create(
            @RequestBody CreateFromTemplateRequest body,
            HttpServletRequest request) {
        ThrowUtils.throwIf(body == null || body.templateId() == null,
                ErrorCode.PARAMS_ERROR, "templateId 不能为空");
        return ResultUtils.success(templateService.create(
                body.templateId(), body.appName(), userService.getLoginUser(request)));
    }

    @DeleteMapping("/v1/apps/{appId}/sample-data")
    public BaseResponse<Integer> clearSampleData(@PathVariable Long appId,
                                                 HttpServletRequest request) {
        return ResultUtils.success(templateService.clearSampleData(
                appId, userService.getLoginUser(request)));
    }
}
