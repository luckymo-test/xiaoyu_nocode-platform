-- 生成应用的业务流量分析，与平台 AI 调用日志分离。

create table if not exists app_analytics_event
(
    id          bigint       not null primary key,
    appId       bigint       not null,
    workspaceId bigint       not null,
    revisionId  bigint       null,
    eventName   varchar(64)  not null,
    sessionId   varchar(128) null,
    path        varchar(1024) null,
    referrer    varchar(1024) null,
    deviceType  varchar(32)  null,
    region      varchar(64)  null,
    metadata    longtext     null,
    eventTime   datetime     not null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    key idx_analytics_app_time (appId, eventTime),
    key idx_analytics_revision_time (revisionId, eventTime)
) comment 'App 业务分析原始事件' collate = utf8mb4_unicode_ci;

create table if not exists app_analytics_daily
(
    id          bigint       not null primary key,
    appId       bigint       not null,
    revisionId  bigint       null,
    statDate    date         not null,
    eventName   varchar(64)  not null,
    eventCount  bigint       default 0 not null,
    createTime  datetime     default CURRENT_TIMESTAMP not null,
    updateTime  datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_analytics_daily (appId, revisionId, statDate, eventName),
    key idx_analytics_daily_app_date (appId, statDate)
) comment 'App 业务分析日聚合' collate = utf8mb4_unicode_ci;
