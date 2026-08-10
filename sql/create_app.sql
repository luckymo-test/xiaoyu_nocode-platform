-- 切换库
use xiaoyu_zerocode;
-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;

-- 功能优化新增字段
ALTER TABLE app
    ADD COLUMN description    varchar(512) NULL COMMENT '应用描述' AFTER cover,
    ADD COLUMN tags           varchar(512) NULL COMMENT '标签(逗号分隔)' AFTER description,
    ADD COLUMN visibility     varchar(16)  NOT NULL DEFAULT 'public' COMMENT '可见范围: public/private' AFTER tags,
    ADD COLUMN generateStatus varchar(16)  NOT NULL DEFAULT 'not_generated' COMMENT '生成状态: not_generated/generating/done/stopped/failed' AFTER visibility,
    ADD COLUMN featuredStatus tinyint      NOT NULL DEFAULT 0 COMMENT '精选状态: 0未申请/1待审核/2已推荐/3已拒绝' AFTER generateStatus;
