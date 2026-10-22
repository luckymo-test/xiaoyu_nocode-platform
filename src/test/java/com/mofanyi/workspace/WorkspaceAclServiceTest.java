package com.mofanyi.workspace;

import com.mofanyi.entity.Workspace;
import com.mofanyi.entity.WorkspaceMember;
import com.mofanyi.mapper.WorkspaceMapper;
import com.mofanyi.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceAclServiceTest {

    @Test
    @DisplayName("Owner 拥有全部权限，Viewer 不能生成，Editor 可以生成")
    void enforcesRolePermissions() {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
        WorkspaceAclService service = new WorkspaceAclService(workspaceMapper, memberMapper);
        Workspace workspace = new Workspace();
        workspace.setId(10L);
        workspace.setOwnerId(1L);
        when(workspaceMapper.selectOneById(10L)).thenReturn(workspace);
        when(memberMapper.selectOneByQuery(any())).thenReturn(member("VIEWER"), member("EDITOR"));

        assertDoesNotThrow(() -> service.require(
                10L, 1L, WorkspacePermission.MANAGE_MEMBERS));
        assertThrows(RuntimeException.class, () -> service.require(
                10L, 2L, WorkspacePermission.GENERATE));
        assertDoesNotThrow(() -> service.require(
                10L, 3L, WorkspacePermission.GENERATE));
    }

    private WorkspaceMember member(String role) {
        WorkspaceMember member = new WorkspaceMember();
        member.setMemberRole(role);
        return member;
    }
}
