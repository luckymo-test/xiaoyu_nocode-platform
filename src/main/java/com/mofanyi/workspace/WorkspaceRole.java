package com.mofanyi.workspace;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;

import java.util.Locale;
import java.util.Set;

public enum WorkspaceRole {
    OWNER(Set.of(WorkspacePermission.values())),
    ADMIN(Set.of(WorkspacePermission.VIEW, WorkspacePermission.EDIT,
            WorkspacePermission.GENERATE, WorkspacePermission.PUBLISH,
            WorkspacePermission.DEPLOY, WorkspacePermission.MANAGE_ASSETS,
            WorkspacePermission.MANAGE_MEMBERS)),
    EDITOR(Set.of(WorkspacePermission.VIEW, WorkspacePermission.EDIT,
            WorkspacePermission.GENERATE, WorkspacePermission.MANAGE_ASSETS)),
    VIEWER(Set.of(WorkspacePermission.VIEW));

    private final Set<WorkspacePermission> permissions;

    WorkspaceRole(Set<WorkspacePermission> permissions) {
        this.permissions = permissions;
    }

    public boolean allows(WorkspacePermission permission) {
        return permissions.contains(permission);
    }

    public static WorkspaceRole from(String value) {
        ThrowUtils.throwIf(value == null || value.isBlank(), ErrorCode.PARAMS_ERROR);
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.mofanyi.exception.BusinessException(
                    ErrorCode.PARAMS_ERROR, "未知工作区角色");
        }
    }
}
