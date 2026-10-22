package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.User;
import com.mofanyi.entity.Workspace;
import com.mofanyi.entity.WorkspaceAuditLog;
import com.mofanyi.entity.WorkspaceMember;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.UserMapper;
import com.mofanyi.mapper.WorkspaceAuditLogMapper;
import com.mofanyi.mapper.WorkspaceMapper;
import com.mofanyi.mapper.WorkspaceMemberMapper;
import com.mofanyi.model.vo.WorkspaceMemberVO;
import com.mofanyi.workspace.WorkspaceAclService;
import com.mofanyi.workspace.WorkspacePermission;
import com.mofanyi.workspace.WorkspaceRole;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkspaceMemberService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceAuditLogMapper auditMapper;
    private final UserMapper userMapper;
    private final WorkspaceAclService aclService;

    public WorkspaceMemberService(WorkspaceMapper workspaceMapper,
                                  WorkspaceMemberMapper memberMapper,
                                  WorkspaceAuditLogMapper auditMapper,
                                  UserMapper userMapper,
                                  WorkspaceAclService aclService) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.auditMapper = auditMapper;
        this.userMapper = userMapper;
        this.aclService = aclService;
    }

    public List<WorkspaceMemberVO> list(Long workspaceId, User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        aclService.require(workspaceId, actor.getId(), WorkspacePermission.VIEW);
        List<WorkspaceMember> members = memberMapper.selectListByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId)
                .orderBy("createTime", true));
        Map<Long, User> users = userMapper.selectListByIds(members.stream()
                        .map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        return members.stream().map(member -> {
            User user = users.get(member.getUserId());
            WorkspaceMemberVO vo = new WorkspaceMemberVO();
            vo.setId(member.getId());
            vo.setWorkspaceId(member.getWorkspaceId());
            vo.setUserId(member.getUserId());
            vo.setRole(member.getMemberRole());
            vo.setCreateTime(member.getCreateTime());
            if (user != null) {
                vo.setUserAccount(user.getUserAccount());
                vo.setUserName(user.getUserName());
            }
            return vo;
        }).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkspaceMemberVO add(Long workspaceId, String userAccount, String role,
                                 User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(userAccount), ErrorCode.PARAMS_ERROR, "账号不能为空");
        WorkspaceRole targetRole = WorkspaceRole.from(role);
        ThrowUtils.throwIf(targetRole == WorkspaceRole.OWNER, ErrorCode.PARAMS_ERROR,
                "不能直接添加 OWNER");
        aclService.require(workspaceId, actor.getId(), WorkspacePermission.MANAGE_MEMBERS);
        User target = userMapper.selectOneByQuery(QueryWrapper.create()
                .eq("userAccount", userAccount.trim()).limit(1));
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "目标用户不存在");
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        ThrowUtils.throwIf(target.getId().equals(workspace.getOwnerId()),
                ErrorCode.PARAMS_ERROR, "所有者无需添加为成员");

        WorkspaceMember existing = memberMapper.selectOneByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId)
                .eq("userId", target.getId())
                .limit(1));
        if (existing == null) {
            WorkspaceMember deleted = LogicDeleteManager.execWithoutLogicDelete(() ->
                    memberMapper.selectOneByQuery(QueryWrapper.create()
                            .eq("workspaceId", workspaceId)
                            .eq("userId", target.getId())
                            .limit(1)));
            existing = deleted;
        }
        LocalDateTime now = LocalDateTime.now();
        WorkspaceMember member;
        if (existing != null) {
            existing.setMemberRole(targetRole.name());
            existing.setUpdateTime(now);
            existing.setIsDelete(0);
            WorkspaceMember toUpdate = existing;
            LogicDeleteManager.execWithoutLogicDelete(() -> memberMapper.update(toUpdate));
            member = existing;
        } else {
            member = WorkspaceMember.builder()
                    .workspaceId(workspaceId)
                    .userId(target.getId())
                    .memberRole(targetRole.name())
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            ThrowUtils.throwIf(memberMapper.insertSelective(member) == 0 || member.getId() == null,
                    ErrorCode.OPERATION_ERROR, "添加成员失败");
        }
        audit(workspaceId, actor.getId(), "MEMBER_ADD",
                String.valueOf(target.getId()), "role=" + targetRole.name());
        WorkspaceMemberVO vo = new WorkspaceMemberVO();
        vo.setId(member.getId());
        vo.setWorkspaceId(workspaceId);
        vo.setUserId(target.getId());
        vo.setUserAccount(target.getUserAccount());
        vo.setUserName(target.getUserName());
        vo.setRole(targetRole.name());
        vo.setCreateTime(member.getCreateTime());
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long workspaceId, Long userId, User actor) {
        ThrowUtils.throwIf(actor == null || actor.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR);
        aclService.require(workspaceId, actor.getId(), WorkspacePermission.MANAGE_MEMBERS);
        Workspace workspace = workspaceMapper.selectOneById(workspaceId);
        ThrowUtils.throwIf(workspace == null, ErrorCode.NOT_FOUND_ERROR, "工作区不存在");
        ThrowUtils.throwIf(userId.equals(workspace.getOwnerId()), ErrorCode.PARAMS_ERROR,
                "不能移除工作区所有者");
        int removed = memberMapper.deleteByQuery(QueryWrapper.create()
                .eq("workspaceId", workspaceId).eq("userId", userId));
        if (removed > 0) {
            audit(workspaceId, actor.getId(), "MEMBER_REMOVE",
                    String.valueOf(userId), null);
        }
        return removed > 0;
    }

    private void audit(Long workspaceId, Long actorId, String action,
                       String resourceId, String detail) {
        auditMapper.insertSelective(WorkspaceAuditLog.builder()
                .workspaceId(workspaceId)
                .actorId(actorId)
                .action(action)
                .resourceType("WORKSPACE_MEMBER")
                .resourceId(resourceId)
                .detail(detail)
                .createTime(LocalDateTime.now())
                .build());
    }
}
