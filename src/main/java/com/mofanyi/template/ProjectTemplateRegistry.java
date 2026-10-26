package com.mofanyi.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProjectTemplateRegistry {

    private final Map<String, ProjectTemplateDefinition> templates = new LinkedHashMap<>();

    public ProjectTemplateRegistry(ObjectMapper objectMapper) {
        load(objectMapper, "task-management");
        load(objectMapper, "customer-management");
        load(objectMapper, "content-publishing");
        load(objectMapper, "client-selection");
    }

    public List<ProjectTemplateDefinition> list() {
        return List.copyOf(templates.values());
    }

    public ProjectTemplateDefinition require(String id) {
        ProjectTemplateDefinition value = id == null ? null : templates.get(id);
        ThrowUtils.throwIf(value == null, ErrorCode.NOT_FOUND_ERROR, "项目模板不存在");
        return value;
    }

    private void load(ObjectMapper mapper, String name) {
        try {
            String json = new ClassPathResource(
                    "project-templates/" + name + ".json")
                    .getContentAsString(StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(json);
            ProjectTemplateDefinition definition = new ProjectTemplateDefinition(
                    root.path("id").asText(),
                    root.path("displayName").asText(),
                    root.path("description").asText(),
                    root.path("initialPrompt").asText(),
                    root.path("visualTemplate").asText(),
                    root.path("schema"));
            templates.put(definition.id(), definition);
        } catch (Exception e) {
            throw new IllegalStateException("加载项目模板失败：" + name, e);
        }
    }
}
