package com.mofanyi.runtime;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.AgentPlan;
import com.mofanyi.entity.AppRuntimeSchema;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.mapper.AgentPlanMapper;
import com.mofanyi.mapper.AppRuntimeSchemaMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class AppRuntimeSchemaService {

    private final AgentPlanMapper planMapper;
    private final AppRuntimeSchemaMapper schemaMapper;
    private final ObjectMapper objectMapper;

    public AppRuntimeSchemaService(AgentPlanMapper planMapper,
                                   AppRuntimeSchemaMapper schemaMapper,
                                   ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.schemaMapper = schemaMapper;
        this.objectMapper = objectMapper;
    }

    public AppRuntimeSchema registerFromConfirmedPlan(GenerationJob job, Long revisionId) {
        if (job == null || revisionId == null || job.getWorkflowVersion() == null
                || job.getWorkflowVersion() < 2) {
            return null;
        }
        AgentPlan plan = planMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", job.getId())
                .eq("status", "CONFIRMED")
                .orderBy("planVersion", false)
                .limit(1));
        if (plan == null) {
            throw new IllegalStateException("已确认任务缺少 AgentPlan");
        }
        String schemaJson = plan.getArchitectureJson();
        validate(schemaJson);
        long version = schemaMapper.selectCountByQuery(QueryWrapper.create()
                .eq("appId", job.getAppId())) + 1;
        AppRuntimeSchema schema = AppRuntimeSchema.builder()
                .workspaceId(job.getWorkspaceId())
                .appId(job.getAppId())
                .revisionId(revisionId)
                .schemaVersion((int) version)
                .schemaJson(schemaJson)
                .sha256(sha256(schemaJson))
                .createTime(LocalDateTime.now())
                .build();
        schemaMapper.insertSelective(schema);
        return schema;
    }

    public AppRuntimeSchema active(Long appId) {
        return schemaMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("schemaVersion", false)
                .limit(1));
    }

    public JsonNode requireSchema(Long appId) {
        AppRuntimeSchema schema = active(appId);
        if (schema == null) {
            throw new IllegalStateException("应用尚未发布 Data/API Schema");
        }
        try {
            return objectMapper.readTree(schema.getSchemaJson());
        } catch (Exception e) {
            throw new IllegalStateException("应用 Schema 损坏", e);
        }
    }

    private void validate(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("models").isArray() || root.path("models").isEmpty()) {
                throw new IllegalStateException("ProjectSchema 缺少 models");
            }
            if (!root.path("permissions").isArray()) {
                throw new IllegalStateException("ProjectSchema 缺少 permissions");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ProjectSchema 不是有效 JSON", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 Schema 摘要失败", e);
        }
    }
}
