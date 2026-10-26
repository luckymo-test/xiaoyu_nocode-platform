package com.mofanyi.runtime;

public record AppRuntimeIdentity(
        Long workspaceId,
        Long appId,
        String actorId,
        String role,
        Long shareId,
        boolean anonymous) {
}
