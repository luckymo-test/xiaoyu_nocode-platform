-- 通用素材库：图片、视频、音频、3D、字体等。

create table if not exists app_asset
(
    id                bigint       not null primary key,
    workspaceId       bigint       not null,
    ownerId           bigint       not null,
    assetType         varchar(32)  not null comment 'IMAGE/VIDEO/AUDIO/MODEL_3D/FONT/OTHER',
    sourceType        varchar(32)  not null comment 'UPLOAD/AI_GENERATED/STOCK/COMMUNITY',
    displayName       varchar(256) not null,
    originalUri       varchar(1024) not null,
    sha256            varchar(64)  not null,
    mimeType          varchar(128) not null,
    extension         varchar(32)  null,
    sizeBytes         bigint       not null,
    width             int          null,
    height            int          null,
    durationMs        bigint       null,
    license           varchar(256) null,
    visibility        varchar(16)  default 'private' not null,
    moderationStatus  varchar(32)  default 'PENDING' not null,
    metadata          longtext     null,
    createTime        datetime     default CURRENT_TIMESTAMP not null,
    updateTime        datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    isDelete          tinyint      default 0 not null,
    unique key uk_asset_workspace_hash (workspaceId, assetType, sha256),
    key idx_asset_workspace_create (workspaceId, createTime),
    key idx_asset_owner_create (ownerId, createTime)
) comment '通用素材' collate = utf8mb4_unicode_ci;

create table if not exists app_asset_variant
(
    id          bigint       not null primary key,
    assetId     bigint       not null,
    variantType varchar(32)  not null comment 'ORIGINAL/THUMBNAIL/640/1280/2400/PREVIEW/POSTER',
    uri         varchar(1024) not null,
    format      varchar(32)  null,
    width       int          null,
    height      int          null,
    sizeBytes   bigint       not null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    unique key uk_asset_variant (assetId, variantType),
    key idx_asset_variant_asset (assetId)
) comment '素材派生版本' collate = utf8mb4_unicode_ci;
