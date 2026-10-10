-- 小玉零代码平台：初始表结构（由 sql/create_*.sql 收敛而来，plan-refactor 6.4）
--
-- 说明：
-- - 这里**不含** `create database` / `use`：Flyway 连的是 spring.datasource.url 指定的库，
--   库本身由运维（或 spring.flyway.create-schemas）负责创建。
-- - 保持 `if not exists` 幂等写法：已有库通过 baseline-on-migrate + baseline-version=0 接入时，
--   V1 仍会被执行一次，必须能安全跑在"表已存在"的库上。

-- 用户表
create table if not exists user
(
    id            bigint auto_increment comment 'id' primary key,
    userAccount   varchar(256)                           not null comment '账号',
    userPassword  varchar(512)                           not null comment '密码',
    userName      varchar(256)                           null comment '用户昵称',
    userAvatar    varchar(1024)                          null comment '用户头像',
    userProfile   varchar(512)                           null comment '用户简介',
    userRole      varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime      datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime    datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint      default 0                 not null comment '是否删除',
    vipExpireTime datetime     null comment '会员过期时间',
    vipCode       varchar(128) null comment '会员兑换码',
    vipNumber     bigint       null comment '会员编号',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 应用表
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
    UNIQUE KEY uk_deployKey (deployKey),
    INDEX idx_appName (appName),
    INDEX idx_userId (userId)
) comment '应用' collate = utf8mb4_unicode_ci;

-- 应用嵌入图片表：记录生成网页时嵌入的真实图片（1:N）
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

-- 对话历史表
create table if not exists chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    parentId    bigint   null comment '父消息id（用于上下文关联）',
    INDEX idx_appId (appId),
    INDEX idx_createTime (createTime),
    INDEX idx_appId_createTime (appId, createTime)
) comment '对话历史' collate = utf8mb4_unicode_ci;

-- AI 调用明细表：每次模型调用一行（高基数维度：用户/应用/错误），聚合指标走 Prometheus
create table if not exists ai_call_log
(
    id              bigint auto_increment comment 'id' primary key,
    appId           bigint      null comment '应用 id',
    userId          bigint      null comment '用户 id',
    callType        varchar(32) null comment '调用类型 html/multi_file/vue/modify/fix',
    model           varchar(64) null comment '模型名称',
    maxOutputTokens int         null comment '本次调用的最大输出 token 配置',
    inputTokens     int         null comment '输入 token',
    outputTokens    int         null comment '输出 token（含推理）',
    totalTokens     int         null comment '总 token',
    durationMs      bigint      null comment '响应时长（毫秒）',
    finishReason    varchar(64) null comment '模型停止原因 stop/length/tool_calls',
    status          varchar(16) null comment '调用状态 success/error',
    errorMsg        text        null comment '失败时的错误信息',
    createTime      datetime default CURRENT_TIMESTAMP not null comment '创建时间（请求时间）',
    INDEX idx_appId_createTime (appId, createTime),
    INDEX idx_userId_createTime (userId, createTime),
    INDEX idx_createTime (createTime)
) comment 'AI 调用明细' collate = utf8mb4_unicode_ci;
