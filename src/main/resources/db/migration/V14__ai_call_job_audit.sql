-- 将模型调用明细归属到具体生成 Job，供 Revision Manifest 和质量对比使用。
alter table ai_call_log
    add column jobId bigint null comment '生成 Job id；非 Job 调用为空' after appId,
    add index idx_ai_call_job_time (jobId, createTime);
