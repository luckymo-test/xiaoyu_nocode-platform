-- 切换库
use xiaoyu_zerocode;

-- AI 调用明细表：每次模型调用一行（高基数维度：用户/应用/错误），聚合指标走 Prometheus
create table ai_call_log
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
