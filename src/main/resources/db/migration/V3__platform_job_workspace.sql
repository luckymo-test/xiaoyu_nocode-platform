-- Phase 0/1：默认个人工作区、资源归属，以及持久化生成任务/事件/Outbox。
-- MySQL 5.7 兼容：JSON 统一使用 LONGTEXT，由应用层校验。

create table if not exists workspace
(
    id          bigint       not null comment '工作区 id' primary key,
    ownerId     bigint       not null comment '所有者用户 id',
    workspaceName varchar(128) not null comment '工作区名称',
    visibility  varchar(16)  default 'private' not null comment '可见范围: private/team/public',
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    updateTime  datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete    tinyint      default 0 not null,
    unique key uk_workspace_owner_name (ownerId, workspaceName),
    index idx_workspace_owner (ownerId)
) comment '工作区' collate = utf8mb4_unicode_ci;

create table if not exists workspace_member
(
    id          bigint       not null comment '成员关系 id' primary key,
    workspaceId bigint       not null comment '工作区 id',
    userId      bigint       not null comment '用户 id',
    memberRole  varchar(32)  not null comment 'OWNER/ADMIN/EDITOR/VIEWER',
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    updateTime  datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete    tinyint      default 0 not null,
    unique key uk_workspace_member (workspaceId, userId),
    index idx_workspace_member_user (userId)
) comment '工作区成员' collate = utf8mb4_unicode_ci;

-- 已有用户升级时，使用 userId 作为默认个人工作区 id，避免额外映射表。
insert into workspace (id, ownerId, workspaceName, visibility)
select u.id, u.id, concat(coalesce(u.userName, u.userAccount), '的个人空间'), 'private'
from user u
where u.isDelete = 0
  and not exists (select 1 from workspace w where w.ownerId = u.id and w.isDelete = 0);

insert into workspace_member (id, workspaceId, userId, memberRole)
select w.id, w.id, w.ownerId, 'OWNER'
from workspace w
where not exists (
    select 1 from workspace_member wm
    where wm.workspaceId = w.id and wm.userId = w.ownerId and wm.isDelete = 0
);

alter table app
    add column workspaceId bigint null comment '所属工作区 id' after userId;

update app a
join workspace w on w.ownerId = a.userId and w.isDelete = 0
set a.workspaceId = w.id
where a.workspaceId is null;

alter table app
    add index idx_app_workspace (workspaceId);

create table if not exists generation_job
(
    id                  bigint       not null comment '生成任务 id' primary key,
    workspaceId         bigint       not null comment '工作区 id',
    appId               bigint       not null comment '应用 id',
    requestedBy         bigint       not null comment '发起用户 id',
    requestType         varchar(32)  not null comment 'generate/modify/repair',
    idempotencyKey      varchar(128) not null comment '客户端幂等键',
    requestHash         varchar(64)  not null comment '请求摘要',
    requestPayload      longtext     not null comment '原始请求 JSON',
    userMessageId       bigint       null comment '创建 Job 时固化的用户消息 id',
    status               varchar(32)  not null comment 'QUEUED/RUNNING/WAITING_RETRY/CANCEL_REQUESTED/SUCCEEDED/FAILED/CANCELLED',
    currentStage         varchar(64)  null comment '当前阶段',
    codeGenType          varchar(64)  null comment '当前兼容的生成类型',
    baseRevisionId       bigint       null comment '起点版本',
    candidateRevisionId  bigint       null comment '候选版本',
    publishedRevisionId  bigint       null comment '当前发布版本',
    visibility           varchar(16)  default 'private' not null comment '资源可见范围',
    priority             int          default 0 not null,
    attempt              int          default 0 not null,
    maxAttempts          int          default 1 not null,
    leaseOwner           varchar(128) null,
    leaseExpiresAt       datetime     null,
    fencingToken         bigint       default 0 not null,
    nextEventSeq         bigint       default 0 not null,
    errorType            varchar(64)  null,
    errorMessage         text         null,
    createTime           datetime     default CURRENT_TIMESTAMP not null,
    updateTime           datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    activeLockKey        varchar(64) generated always as (
        case
            when status in ('QUEUED', 'RUNNING', 'WAITING_RETRY', 'CANCEL_REQUESTED')
            then concat('app:', appId)
            else null
        end
    ) stored comment '同一 App 只允许一个活跃 Job',
    unique key uk_job_idempotency (appId, idempotencyKey),
    unique key uk_job_active_app (activeLockKey),
    key idx_job_app_create (appId, createTime),
    key idx_job_status_lease (status, leaseExpiresAt),
    key idx_job_workspace_create (workspaceId, createTime)
) comment '持久化生成任务' collate = utf8mb4_unicode_ci;

create table if not exists generation_job_event
(
    id          bigint       not null primary key,
    jobId       bigint       not null,
    seq         bigint       not null,
    eventType   varchar(64)  not null comment 'queued/stage/progress/reasoning/completed/failed/cancelled',
    stage       varchar(64)  null,
    visibility  varchar(16)  default 'user' not null,
    payload     longtext     null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    unique key uk_job_event_seq (jobId, seq),
    key idx_job_event_read (jobId, seq)
) comment '生成任务事件日志' collate = utf8mb4_unicode_ci;

create table if not exists generation_outbox
(
    id            bigint       not null primary key,
    jobId         bigint       not null,
    eventType     varchar(64)  not null,
    payload       longtext     not null,
    status        varchar(16)  not null comment 'PENDING/DISPATCHING/PUBLISHED',
    retryCount    int          default 0 not null,
    nextAttemptAt datetime     not null,
    createTime    datetime     default CURRENT_TIMESTAMP not null,
    updateTime    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    key idx_outbox_dispatch (status, nextAttemptAt),
    key idx_outbox_job (jobId)
) comment '事务 Outbox' collate = utf8mb4_unicode_ci;
