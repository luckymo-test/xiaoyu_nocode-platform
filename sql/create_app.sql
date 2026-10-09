-- 切换库
use xiaoyu_zerocode;
-- 应用表（幂等：可重复执行。追加过的字段已合并进建表语句，避免重复执行 ALTER 报错）
create table if not exists app
(
    id               bigint auto_increment comment 'id' primary key,
    appName          varchar(256)                         null comment '应用名称',
    cover            varchar(512)                         null comment '应用封面',
    description      varchar(512)                         null comment '应用描述',
    tags             varchar(512)                         null comment '标签(逗号分隔)',
    visibility       varchar(16)  default 'public'        not null comment '可见范围: public/private',
    generateStatus   varchar(16)  default 'not_generated' not null comment '生成状态: not_generated/generating/done/stopped/failed',
    featuredStatus   tinyint      default 0               not null comment '精选状态: 0未申请/1待审核/2已推荐/3已拒绝',
    compressPictures varchar(512)                         null comment '压缩图',
    initPrompt       text                                 null comment '应用初始化的 prompt',
    codeGenType      varchar(64)                          null comment '代码生成类型（枚举）',
    deployKey        varchar(64)                          null comment '部署标识',
    deployedTime     datetime                             null comment '部署时间',
    priority         int          default 0               not null comment '优先级',
    userId           bigint                               not null comment '创建用户id',
    editTime         datetime default CURRENT_TIMESTAMP   not null comment '编辑时间',
    createTime       datetime default CURRENT_TIMESTAMP   not null comment '创建时间',
    updateTime       datetime default CURRENT_TIMESTAMP   not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete         tinyint      default 0               not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;

-- 应用嵌入图片表：记录生成网页时嵌入的真实图片（1:N，一个应用可有多张图）
create table if not exists app_image
(
    id         bigint auto_increment comment 'id' primary key,
    appId      bigint       not null comment '应用id',
    imageUrl   varchar(512) not null comment '图片 COS URL',
    imageType  varchar(32)  null comment '用途: main/embed',
    sortOrder  int          default 0 null comment '排序',
    createTime datetime     default CURRENT_TIMESTAMP null comment '创建时间',
    updateTime datetime     default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP null comment '更新时间',
    isDelete   tinyint      default 0 not null comment '是否删除',
    INDEX idx_appId (appId)
) comment '应用嵌入图片' collate = utf8mb4_unicode_ci;

