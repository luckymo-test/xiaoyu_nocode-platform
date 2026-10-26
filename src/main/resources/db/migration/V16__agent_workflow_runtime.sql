-- Workflow v2, generated-app Data/API, deployment ledger and usage aggregates.

alter table generation_job
    add column workflowVersion int default 1 not null comment '1=legacy generation, 2=confirmed multi-agent workflow' after requestType;
alter table generation_job
    add column confirmedPlanHash varchar(64) null after workflowVersion;
alter table generation_job
    add column confirmedAt datetime null after confirmedPlanHash;
alter table generation_job
    add column confirmedBy bigint null after confirmedAt;

alter table app
    add column projectTemplateId varchar(64) null after codeGenType;

alter table generation_job
    drop index uk_job_active_app,
    drop column activeLockKey;
alter table generation_job
    add column activeLockKey varchar(64) generated always as (
        case
            when status in ('QUEUED', 'RUNNING', 'WAITING_RETRY', 'CANCEL_REQUESTED',
                            'WAITING_CONFIRMATION', 'PAUSE_REQUESTED', 'PAUSED')
            then concat('app:', appId)
            else null
        end
    ) stored comment '同一 App 只允许一个活跃 Job',
    add unique key uk_job_active_app (activeLockKey);

create table if not exists agent_plan
(
    id                  bigint       not null primary key,
    jobId               bigint       not null,
    appId               bigint       not null,
    planVersion         int          not null,
    status              varchar(32)  not null comment 'PROPOSED/CONFIRMED/SUPERSEDED',
    requirementJson     longtext     not null,
    architectureJson    longtext     not null,
    uiJson              longtext     not null,
    executionPlanJson   longtext     null,
    planHash            varchar(64)  not null,
    confirmedBy         bigint       null,
    confirmedAt         datetime     null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_agent_plan_job_version (jobId, planVersion),
    key idx_agent_plan_job_hash (jobId, planHash)
) comment 'Confirmed requirement, architecture and UI plan' collate = utf8mb4_unicode_ci;

create table if not exists agent_step
(
    id                  bigint       not null primary key,
    jobId               bigint       not null,
    planId              bigint       null,
    stepKey             varchar(128) not null,
    agentType           varchar(32)  not null comment 'REQUIREMENT/PLANNER/ARCHITECT/UI/CODER/REVIEWER/DEPLOY',
    title               varchar(256) not null,
    status              varchar(32)  not null comment 'PENDING/BLOCKED/RUNNING/PAUSED/SUCCEEDED/FAILED/SKIPPED',
    dependenciesJson    longtext     null,
    inputJson           longtext     null,
    outputJson          longtext     null,
    inputArtifactId     bigint       null,
    outputArtifactId    bigint       null,
    attempt             int          default 0 not null,
    maxAttempts         int          default 2 not null,
    errorType           varchar(64)  null,
    errorMessage        text         null,
    startedAt           datetime     null,
    finishedAt          datetime     null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_agent_step_job_key (jobId, stepKey),
    key idx_agent_step_job_status (jobId, status)
) comment 'Persisted agent task DAG node' collate = utf8mb4_unicode_ci;

create table if not exists agent_tool_call
(
    id                  bigint       not null primary key,
    jobId               bigint       not null,
    stepId              bigint       null,
    toolCallId          varchar(128) null,
    toolName            varchar(64)  not null,
    argumentsJson       longtext     null,
    resultSummary       text         null,
    status              varchar(16)  not null comment 'RUNNING/SUCCEEDED/FAILED',
    durationMs          bigint       null,
    filePath            varchar(1024) null,
    beforeSha256        varchar(64)  null,
    afterSha256         varchar(64)  null,
    errorMessage        text         null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    key idx_agent_tool_job (jobId, createTime),
    key idx_agent_tool_step (stepId, createTime)
) comment 'Audited legal tool invocation' collate = utf8mb4_unicode_ci;

create table if not exists app_runtime_schema
(
    id                  bigint       not null primary key,
    workspaceId         bigint       not null,
    appId               bigint       not null,
    revisionId          bigint       not null,
    schemaVersion       int          not null,
    schemaJson          longtext     not null,
    sha256              varchar(64)  not null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    unique key uk_runtime_schema_revision (revisionId),
    key idx_runtime_schema_app (workspaceId, appId, schemaVersion)
) comment 'Versioned generated app schema' collate = utf8mb4_unicode_ci;

create table if not exists app_model_record
(
    id                  bigint       not null primary key,
    workspaceId         bigint       not null,
    appId               bigint       not null,
    modelName           varchar(64)  not null,
    recordId            varchar(128) not null,
    dataJson            longtext     not null,
    recordVersion       bigint       default 1 not null,
    ownerId             varchar(128) null,
    isSample            tinyint      default 0 not null,
    isDelete            tinyint      default 0 not null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_app_model_record (appId, modelName, recordId),
    key idx_app_model_query (workspaceId, appId, modelName, isDelete, updateTime)
) comment 'Tenant-scoped generated app records' collate = utf8mb4_unicode_ci;

create table if not exists app_share_link
(
    id                  bigint       not null primary key,
    workspaceId         bigint       not null,
    appId               bigint       not null,
    tokenHash           varchar(64)  not null,
    permissionsJson     longtext     not null,
    expiresAt           datetime     null,
    revokedAt           datetime     null,
    createdBy           bigint       not null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    unique key uk_app_share_token (tokenHash),
    key idx_app_share_app (workspaceId, appId, expiresAt)
) comment 'Scoped anonymous share links' collate = utf8mb4_unicode_ci;

create table if not exists app_visitor
(
    id                  bigint       not null primary key,
    workspaceId         bigint       not null,
    appId               bigint       not null,
    shareId             bigint       not null,
    visitorId           varchar(64)  not null,
    displayName         varchar(80)  null,
    lastSeenAt          datetime     null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    unique key uk_app_visitor (appId, visitorId),
    key idx_app_visitor_share (shareId, lastSeenAt)
) comment 'Anonymous visitor identity scoped to an app share' collate = utf8mb4_unicode_ci;

create table if not exists app_deployment
(
    id                  bigint       not null primary key,
    workspaceId         bigint       not null,
    appId               bigint       not null,
    revisionId          bigint       not null,
    deployKey           varchar(32)  not null,
    url                 varchar(1024) not null,
    status              varchar(24)  not null comment 'PREPARING/ACTIVE/FAILED/INACTIVE',
    changeSummary       varchar(1000) null,
    createdBy           bigint       not null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    key idx_app_deployment_app (workspaceId, appId, createTime),
    key idx_app_deployment_key (deployKey, status)
) comment 'Stable deploy key publication ledger' collate = utf8mb4_unicode_ci;

create table if not exists user_usage_daily
(
    id                  bigint       not null primary key,
    userId              bigint       not null,
    statDate            date         not null,
    generationCount     bigint       default 0 not null,
    promptTokens        bigint       default 0 not null,
    completionTokens    bigint       default 0 not null,
    estimatedCostMicros bigint       default 0 not null,
    storageBytes        bigint       default 0 not null,
    createTime          datetime     default CURRENT_TIMESTAMP not null,
    updateTime          datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    unique key uk_user_usage_day (userId, statDate)
) comment 'Daily estimated user usage' collate = utf8mb4_unicode_ci;
