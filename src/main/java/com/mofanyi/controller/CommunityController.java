package com.mofanyi.controller;

import com.mofanyi.annotation.AuthCheck;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.community.PublicationCreateRequest;
import com.mofanyi.model.dto.community.PublicationReviewRequest;
import com.mofanyi.model.vo.AppPublicationVO;
import com.mofanyi.service.CommunityPublicationService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/v1/community/publications")
public class CommunityController {

    private final CommunityPublicationService publicationService;
    private final UserService userService;

    public CommunityController(CommunityPublicationService publicationService,
                               UserService userService) {
        this.publicationService = publicationService;
        this.userService = userService;
    }

    @PostMapping
    public BaseResponse<AppPublicationVO> create(
            @RequestBody PublicationCreateRequest body, HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        User user = userService.getLoginUser(request);
        return ResultUtils.success(publicationService.create(body, user));
    }

    @GetMapping
    public BaseResponse<List<AppPublicationVO>> listPublished() {
        return ResultUtils.success(publicationService.listPublished());
    }

    @GetMapping("/{publicationId}")
    public BaseResponse<AppPublicationVO> getPublished(@PathVariable Long publicationId) {
        return ResultUtils.success(publicationService.getPublished(publicationId));
    }

    @PostMapping("/{publicationId}/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PreAuthorize("hasRole('ADMIN')")
    public BaseResponse<AppPublicationVO> review(@PathVariable Long publicationId,
                                                 @RequestBody PublicationReviewRequest body) {
        ThrowUtils.throwIf(body == null || body.getApproved() == null,
                ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(publicationService.review(
                publicationId, body.getApproved(), body.getReviewNote()));
    }

    @PostMapping("/{publicationId}/takedown")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PreAuthorize("hasRole('ADMIN')")
    public BaseResponse<Boolean> takedown(@PathVariable Long publicationId,
                                          @RequestParam(required = false) String note) {
        return ResultUtils.success(publicationService.takedown(publicationId, note));
    }
}
