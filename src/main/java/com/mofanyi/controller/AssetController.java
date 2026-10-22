package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.vo.AssetVO;
import com.mofanyi.service.AssetRegistryService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/v1/assets")
public class AssetController {

    private final AssetRegistryService assetService;
    private final UserService userService;

    public AssetController(AssetRegistryService assetService, UserService userService) {
        this.assetService = assetService;
        this.userService = userService;
    }

    @PostMapping("/upload")
    public BaseResponse<AssetVO> upload(@RequestParam MultipartFile file,
                                        @RequestParam(required = false) Long workspaceId,
                                        @RequestParam(required = false) String assetType,
                                        @RequestParam(required = false) String visibility,
                                        @RequestParam(required = false) String license,
                                        HttpServletRequest request) {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(assetService.upload(
                workspaceId, assetType, visibility, license, file, loginUser));
    }

    @GetMapping
    public BaseResponse<List<AssetVO>> list(@RequestParam(required = false) Long workspaceId,
                                            @RequestParam(required = false) String assetType,
                                            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(assetService.list(workspaceId, assetType, loginUser));
    }

    @GetMapping("/{assetId}")
    public BaseResponse<AssetVO> get(@PathVariable Long assetId,
                                     HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(assetService.get(assetId, loginUser));
    }
}
