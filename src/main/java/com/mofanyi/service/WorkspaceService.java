package com.mofanyi.service;

import com.mofanyi.entity.User;
import com.mofanyi.entity.Workspace;
import com.mofanyi.entity.WorkspaceMember;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.WorkspaceMapper;
import com.mofanyi.mapper.WorkspaceMemberMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 工作区基础服务。Phase 0 先提供默认个人工作区，后续 ACL/团队能力在此扩展。
 */
@Service
public class WorkspaceService {

    public static final String ROLE_OWNER = "OWNER";
    private static final String VISIBILITY_PRIVATE = "private";

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    public WorkspaceService(WorkspaceMapper workspaceMapper,
                            WorkspaceMemberMapper workspaceMemberMapper) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceMemberMapper = workspaceMemberMapper;
    }

    /**
     * 查询用户默认个人工作区；不存在则创建。迁移后的老用户会直接命中迁移脚本生成的工作区。
     */
    @Transactional(rollbackFor = Exception.class)
    public Workspace ensureDefaultWorkspace(User user) {
        ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.PARAMS_ERROR);
        Workspace existing = workspaceMapper.selectOneByQuery(QueryWrapper.create()
                .eq("ownerId", user.getId())
                .eq("visibility", VISIBILITY_PRIVATE)
                .orderBy("createTime", true)
                .limit(1));
        if (existing != null) {
            ensureOwnerMember(existing, user.getId());
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        String displayName = user.getUserName();
        if (displayName == null || displayName.isBlank()) {
            displayName = user.getUserAccount();
        }
        Workspace workspace = Workspace.builder()
                .ownerId(user.getId())
                .workspaceName(displayName + "的个人空间")
                .visibility(VISIBILITY_PRIVATE)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
        ThrowUtils.throwIf(workspaceMapper.insertSelective(workspace) == 0 || workspace.getId() == null,
                ErrorCode.OPERATION_ERROR, "创建默认工作区失败");
        ensureOwnerMember(workspace, user.getId());
        return workspace;
    }

    /**
     * 校验用户是工作区成员。后续团队权限统一走这里。
     */
    public void requireMember(Long workspaceId, Long userId) {
        ThrowUtils.throwIf(workspaceId == null || workspaceId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        long count = workspaceMemberMapper.selectCountByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId)
                .eq("userId", userId));
        ThrowUtils.throwIf(count == 0, ErrorCode.NO_AUTH_ERROR, "无权访问该工作区");
    }

    private void ensureOwnerMember(Workspace workspace, Long userId) {
        long count = workspaceMemberMapper.selectCountByQuery(QueryWrapper.create()
                .eq("workspaceId", workspace.getId())
                .eq("userId", userId));
        if (count > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        WorkspaceMember member = WorkspaceMember.builder()
                .workspaceId(workspace.getId())
                .userId(userId)
                .memberRole(ROLE_OWNER)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
        ThrowUtils.throwIf(workspaceMemberMapper.insertSelective(member) == 0,
                ErrorCode.OPERATION_ERROR, "创建默认工作区成员失败");
    }
}
