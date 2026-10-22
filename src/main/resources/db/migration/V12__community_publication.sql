-- 社区发布：绑定不可变 Revision，经过审核后公开。

create table if not exists app_publication
(
    id          bigint       not null primary key,
    appId       bigint       not null,
    revisionId  bigint       not null,
    workspaceId bigint       not null,
    publisherId bigint       not null,
    status      varchar(32)  default 'PENDING' not null comment 'PENDING/PUBLISHED/REJECTED/TAKEDOWN',
    title       varchar(256) not null,
    description varchar(2048) null,
    license     varchar(256) null,
    forkAllowed tinyint      default 0 not null,
    reviewNote  varchar(1024) null,
    publishedAt datetime     null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    updateTime  datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_publication_revision (revisionId),
    key idx_publication_status_time (status, createTime),
    key idx_publication_app (appId)
) comment '社区应用发布' collate = utf8mb4_unicode_ci;
