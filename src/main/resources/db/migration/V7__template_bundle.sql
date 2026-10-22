-- Phase 5：Template Bundle 版本锁定。

alter table generation_job
    add column templateBundleId varchar(128) null after stackProfileId,
    add column templateVersion varchar(64) null after templateBundleId,
    add column templateHash varchar(64) null after templateVersion;

alter table app_revision
    add column templateBundleId varchar(128) null after codeGenType,
    add column templateVersion varchar(64) null after templateBundleId,
    add column templateHash varchar(64) null after templateVersion;
