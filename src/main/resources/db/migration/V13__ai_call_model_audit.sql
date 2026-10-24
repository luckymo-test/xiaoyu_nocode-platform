-- 记录一次调用实际命中的供应商、模型与任务路由。
alter table ai_call_log
    add column provider varchar(64) null comment '实际模型供应商' after model,
    add column modelTask varchar(32) null comment 'GENERATE/REPAIR/MODIFY/VISION_CURATE/QUERY_PLAN/COMPRESS/RESEARCH' after provider,
    add index idx_ai_call_model_time (model, createTime),
    add index idx_ai_call_task_time (modelTask, createTime);
