-- Multiple project templates may be created before the first generated
-- revision, so revisionId=0 is a valid shared sentinel. Keep it indexed, not
-- globally unique; generated revisions remain unique at the application layer.

alter table app_runtime_schema
    drop index uk_runtime_schema_revision,
    add key idx_runtime_schema_revision (revisionId);
