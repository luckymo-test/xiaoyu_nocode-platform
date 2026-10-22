package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.workspace.WorkspaceMemberAddRequest;
import com.mofanyi.model.vo.WorkspaceMemberVO;
import com.mofanyi.service.UserService;
import com.mofanyi.service.WorkspaceMemberService;
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
@RequestMapping("/v1/workspaces/{workspaceId}/members")
public class WorkspaceController {

    private final WorkspaceMemberService memberService;
    private final UserService userService;

    public WorkspaceController(WorkspaceMemberService memberService,
                               UserService userService) {
        this.memberService = memberService;
        this.userService = userService;
    }

    @GetMapping
    public BaseResponse<List<WorkspaceMemberVO>> list(@PathVariable Long workspaceId,
                                                      HttpServletRequest request) {
        return ResultUtils.success(memberService.list(
                workspaceId, userService.getLoginUser(request)));
    }

    @PostMapping
    public BaseResponse<WorkspaceMemberVO> add(@PathVariable Long workspaceId,
                                               @RequestBody WorkspaceMemberAddRequest body,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        User actor = userService.getLoginUser(request);
        return ResultUtils.success(memberService.add(
                workspaceId, body.getUserAccount(), body.getRole(), actor));
    }

    @DeleteMapping("/{userId}")
    public BaseResponse<Boolean> remove(@PathVariable Long workspaceId,
                                        @PathVariable Long userId,
                                        HttpServletRequest request) {
        User actor = userService.getLoginUser(request);
        return ResultUtils.success(memberService.remove(workspaceId, userId, actor));
    }
}
