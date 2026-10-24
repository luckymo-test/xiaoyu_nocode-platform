-- Job 创建时固定运行时与技能版本，Worker 启动前核对，避免同名配置漂移。
alter table generation_job
    add column stackProfileVersion varchar(64) null comment 'Job 创建时的 StackProfile 版本' after stackProfileId,
    add column skillsSnapshotJson longtext null comment 'Job 创建时的 Skill id/version 快照' after skillsJson;

alter table app_revision
    add column stackProfileVersion varchar(64) null comment '生成时的 StackProfile 版本' after codeGenType,
    add column skillsSnapshotJson longtext null comment '生成时的 Skill id/version 快照' after skillsJson;
