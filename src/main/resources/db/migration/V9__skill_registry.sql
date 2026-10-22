-- 技能能力包版本锁定。

alter table generation_job
    add column skillsJson longtext null after templateHash;

alter table app_revision
    add column skillsJson longtext null after templateHash;
