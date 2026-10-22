-- Phase 3：失败重试与死信。

create table if not exists generation_dead_letter
(
    id            bigint       not null primary key,
    jobId         bigint       not null,
    workspaceId   bigint       not null,
    appId         bigint       not null,
    requestedBy   bigint       not null,
    attempt       int          not null,
    errorType     varchar(64)  null,
    errorMessage  text         null,
    payload       longtext     null,
    status        varchar(16)  default 'PENDING' not null comment 'PENDING/REPLAYED/DISCARDED',
    replayedBy    bigint       null,
    replayedAt    datetime     null,
    createTime    datetime     default CURRENT_TIMESTAMP not null,
    updateTime    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_dead_job_attempt (jobId, attempt),
    key idx_dead_status_create (status, createTime)
) comment '生成任务死信' collate = utf8mb4_unicode_ci;
