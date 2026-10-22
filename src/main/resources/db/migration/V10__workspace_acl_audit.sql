-- 团队 ACL 与审计基础。

create table if not exists workspace_audit_log
(
    id          bigint       not null primary key,
    workspaceId bigint       not null,
    actorId     bigint       not null,
    action      varchar(64)  not null,
    resourceType varchar(64) not null,
    resourceId  varchar(128) null,
    detail      longtext     null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    key idx_workspace_audit (workspaceId, createTime),
    key idx_workspace_actor (actorId, createTime)
) comment '工作区审计日志' collate = utf8mb4_unicode_ci;
