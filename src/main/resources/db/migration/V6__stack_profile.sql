-- Phase 4：Job 固定使用的 StackProfile。

alter table generation_job
    add column stackProfileId varchar(64) null comment '受控运行时 Profile id' after codeGenType;
