package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.service.AppFileService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/apps/{appId}/files")
public class AppFileController {

    public record SaveFileRequest(String path, String content, String expectedSha256) {
    }

    private final AppFileService fileService;
    private final UserService userService;

    public AppFileController(AppFileService fileService, UserService userService) {
        this.fileService = fileService;
        this.userService = userService;
    }

    @GetMapping("/tree")
    public BaseResponse<List<AppFileService.FileNode>> tree(
            @PathVariable Long appId, HttpServletRequest request) {
        return ResultUtils.success(fileService.tree(
                appId, userService.getLoginUser(request)));
    }

    @GetMapping("/content")
    public BaseResponse<AppFileService.FileContent> content(
            @PathVariable Long appId,
            @RequestParam String path,
            HttpServletRequest request) {
        return ResultUtils.success(fileService.content(
                appId, path, userService.getLoginUser(request)));
    }

    @PutMapping("/content")
    public BaseResponse<AppFileService.FileContent> save(
            @PathVariable Long appId,
            @RequestBody SaveFileRequest body,
            HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(fileService.save(
                appId, body.path(), body.content(), body.expectedSha256(),
                userService.getLoginUser(request)));
    }
}
