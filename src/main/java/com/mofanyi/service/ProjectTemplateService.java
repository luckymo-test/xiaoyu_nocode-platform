package com.mofanyi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.AppModelRecord;
import com.mofanyi.entity.AppRuntimeSchema;
import com.mofanyi.entity.User;
import com.mofanyi.mapper.AppModelRecordMapper;
import com.mofanyi.mapper.AppRuntimeSchemaMapper;
import com.mofanyi.model.dto.app.AppAddRequest;
import com.mofanyi.template.ProjectTemplateDefinition;
import com.mofanyi.template.ProjectTemplateRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
public class ProjectTemplateService {

    public record CreatedProject(Long appId, String templateId, String initialPrompt) {
    }

    private final ProjectTemplateRegistry registry;
    private final AppService appService;
    private final AppAuthService appAuthService;
    private final AppRuntimeSchemaMapper schemaMapper;
    private final AppModelRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    public ProjectTemplateService(ProjectTemplateRegistry registry,
                                  AppService appService,
                                  AppAuthService appAuthService,
                                  AppRuntimeSchemaMapper schemaMapper,
                                  AppModelRecordMapper recordMapper,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.appService = appService;
        this.appAuthService = appAuthService;
        this.schemaMapper = schemaMapper;
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    public List<ProjectTemplateDefinition> list() {
        return registry.list();
    }

    public CreatedProject create(String templateId, String appName, User user) {
        ProjectTemplateDefinition template = registry.require(templateId);
        AppAddRequest request = new AppAddRequest();
        request.setAppName(appName == null || appName.isBlank()
                ? template.displayName() : appName.trim());
        request.setDescription(template.description());
        request.setInitPrompt(template.initialPrompt());
        request.setCodeGenType("vue");
        request.setVisibility("private");
        long appId = appService.addApp(request, user);
        com.mofanyi.entity.App appUpdate = new com.mofanyi.entity.App();
        appUpdate.setId(appId);
        appUpdate.setProjectTemplateId(template.id());
        appService.updateById(appUpdate);
        String schemaJson = template.schema().toString();
        schemaMapper.insertSelective(AppRuntimeSchema.builder()
                .workspaceId(appService.getById(appId).getWorkspaceId())
                .appId(appId)
                .revisionId(0L)
                .schemaVersion(1)
                .schemaJson(schemaJson)
                .sha256(sha256(schemaJson))
                .createTime(LocalDateTime.now())
                .build());
        seed(appId, appService.getById(appId).getWorkspaceId(), user, template.schema());
        return new CreatedProject(appId, template.id(), template.initialPrompt());
    }

    public int clearSampleData(Long appId, User user) {
        appAuthService.requireCanEdit(appId, user);
        return recordMapper.deleteByQuery(com.mybatisflex.core.query.QueryWrapper.create()
                .eq("appId", appId)
                .eq("isSample", 1));
    }

    private void seed(Long appId, Long workspaceId, User user, JsonNode schema) {
        int index = 0;
        for (JsonNode seed : schema.path("seedData")) {
            JsonNode data = seed.path("data");
            String recordId = data.path("id").asText("seed-" + index++);
            LocalDateTime now = LocalDateTime.now();
            recordMapper.insertSelective(AppModelRecord.builder()
                    .workspaceId(workspaceId)
                    .appId(appId)
                    .modelName(seed.path("model").asText())
                    .recordId(recordId)
                    .dataJson(data.toString())
                    .recordVersion(1L)
                    .ownerId("user:" + user.getId())
                    .isSample(1)
                    .isDelete(0)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算模板 Schema 摘要失败", e);
        }
    }
}
