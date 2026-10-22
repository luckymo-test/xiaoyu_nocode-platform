package com.mofanyi.workspace;

import com.mofanyi.entity.Workspace;
import com.mofanyi.entity.WorkspaceMember;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.WorkspaceMapper;
import com.mofanyi.mapper.WorkspaceMemberMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAclService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;

    public WorkspaceAclService(WorkspaceMapper workspaceMapper,
                               WorkspaceMemberMapper memberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
    }

    public void require(Long workspaceId, Long userId, WorkspacePermission permission) {
        ThrowUtils.throwIf(workspaceId == null || workspaceId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(permission == null, ErrorCode.PARAMS_ERROR);
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        ThrowUtils.throwIf(workspace == null, ErrorCode.NOT_FOUND_ERROR, "工作区不存在");
        if (userId.equals(workspace.getOwnerId())) {
            return;
        }
        WorkspaceMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId)
                .eq("userId", userId)
                .limit(1));
        ThrowUtils.throwIf(member == null, ErrorCode.NO_AUTH_ERROR, "不是工作区成员");
        ThrowUtils.throwIf(!WorkspaceRole.from(member.getMemberRole()).allows(permission),
                ErrorCode.NO_AUTH_ERROR, "工作区角色无此权限");
    }

    public WorkspaceRole roleOf(Long workspaceId, Long userId) {
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        ThrowUtils.throwIf(workspace == null, ErrorCode.NOT_FOUND_ERROR, "工作区不存在");
        if (userId != null && userId.equals(workspace.getOwnerId())) {
            return WorkspaceRole.OWNER;
        }
        WorkspaceMember member = memberMapper.selectOneByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId)
                .eq("userId", userId)
                .limit(1));
        ThrowUtils.throwIf(member == null, ErrorCode.NO_AUTH_ERROR, "不是工作区成员");
        return WorkspaceRole.from(member.getMemberRole());
    }
}
