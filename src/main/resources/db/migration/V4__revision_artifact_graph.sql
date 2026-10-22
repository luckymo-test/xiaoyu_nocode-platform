-- Phase 2：不可变 Revision 与 Artifact Graph。

alter table app
    add column currentRevision bigint null comment '当前发布版本 id' after workspaceId;

create table if not exists app_revision
(
    id               bigint       not null comment '版本 id' primary key,
    workspaceId      bigint       not null comment '工作区 id',
    appId            bigint       not null comment '应用 id',
    jobId            bigint       null comment '生成该版本的任务 id',
    revisionNo       bigint       not null comment '应用内递增版本号',
    baseRevisionId   bigint       null comment '起点版本 id',
    status           varchar(32)  not null comment 'CREATED/PUBLISHED/SUPERSEDED/FAILED',
    codeGenType      varchar(64)  not null comment '生成类型',
    sourceUri        varchar(1024) not null comment '不可变源码目录',
    manifest         longtext     null comment 'Revision Manifest JSON',
    validationStatus varchar(64)  null comment '验证状态',
    createdBy        bigint       not null comment '创建用户 id',
    publishedAt      datetime     null,
    createTime       datetime     default CURRENT_TIMESTAMP not null,
    updateTime       datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_revision_app_no (appId, revisionNo),
    unique key uk_revision_job (jobId),
    key idx_revision_app_create (appId, createTime)
) comment '不可变应用版本' collate = utf8mb4_unicode_ci;

create table if not exists app_artifact
(
    id          bigint        not null comment '产物 id' primary key,
    revisionId  bigint        not null comment '版本 id',
    jobId       bigint        null comment '任务 id',
    appId       bigint        not null comment '应用 id',
    workspaceId bigint        not null comment '工作区 id',
    artifactType varchar(64)  not null comment 'SOURCE_TREE/MATERIAL_MANIFEST/RENDER_REPORT_BEFORE/RENDER_REPORT_AFTER/REQUIREMENT_BASELINE/BUILD_OUTPUT',
    scope       varchar(16)   not null comment 'JOB/PROJECT/WORKSPACE/SYSTEM/PUBLIC',
    ownerId     bigint        not null comment '所有者用户 id',
    visibility  varchar(16)   default 'private' not null,
    uri         varchar(1024) not null comment '本地产物 URI',
    sha256      varchar(64)   null comment '内容摘要',
    sizeBytes   bigint        default 0 not null,
    mimeType    varchar(128)  null,
    metadata    longtext      null comment '扩展元数据 JSON',
    createTime  datetime      default CURRENT_TIMESTAMP not null,
    key idx_artifact_revision (revisionId, artifactType),
    key idx_artifact_job (jobId)
) comment '版本产物元数据' collate = utf8mb4_unicode_ci;
