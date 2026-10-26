package com.mofanyi.runtime;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mofanyi.entity.AppModelRecord;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppModelRecordMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AppRuntimeDataService {

    private final AppRuntimeSchemaService schemas;
    private final AppModelRecordMapper records;
    private final ObjectMapper objectMapper;

    public AppRuntimeDataService(AppRuntimeSchemaService schemas,
                                 AppModelRecordMapper records,
                                 ObjectMapper objectMapper) {
        this.schemas = schemas;
        this.records = records;
        this.objectMapper = objectMapper;
    }

    public List<JsonNode> list(AppRuntimeIdentity identity, String model, int limit) {
        JsonNode schema = schemas.requireSchema(identity.appId());
        JsonNode modelSpec = requireModel(schema, model);
        authorize(schema, modelSpec, identity, "READ");
        int size = Math.max(1, Math.min(limit, 200));
        List<JsonNode> result = new ArrayList<>();
        for (AppModelRecord record : records.selectListByQuery(QueryWrapper.create()
                .eq("workspaceId", identity.workspaceId())
                .eq("appId", identity.appId())
                .eq("modelName", model)
                .eq("isDelete", 0)
                .orderBy("updateTime", false)
                .limit(size))) {
            result.add(parse(record.getDataJson()));
        }
        return result;
    }

    public JsonNode get(AppRuntimeIdentity identity, String model, String recordId) {
        JsonNode schema = schemas.requireSchema(identity.appId());
        JsonNode modelSpec = requireModel(schema, model);
        authorize(schema, modelSpec, identity, "READ");
        return parse(requireRecord(identity, model, recordId).getDataJson());
    }

    public JsonNode create(AppRuntimeIdentity identity, String model, JsonNode data) {
        JsonNode schema = schemas.requireSchema(identity.appId());
        JsonNode modelSpec = requireModel(schema, model);
        authorize(schema, modelSpec, identity, "CREATE");
        ObjectNode value = requireObject(data);
        validateFields(modelSpec, value);
        String recordId = value.path("id").asText("");
        if (StrUtil.isBlank(recordId)) {
            recordId = UUID.randomUUID().toString();
            value.put("id", recordId);
        }
        LocalDateTime now = LocalDateTime.now();
        AppModelRecord record = AppModelRecord.builder()
                .workspaceId(identity.workspaceId())
                .appId(identity.appId())
                .modelName(model)
                .recordId(recordId)
                .dataJson(write(value))
                .recordVersion(1L)
                .ownerId(identity.actorId())
                .isSample(0)
                .isDelete(0)
                .createTime(now)
                .updateTime(now)
                .build();
        records.insertSelective(record);
        return value;
    }

    public JsonNode update(AppRuntimeIdentity identity, String model, String recordId,
                           long expectedVersion, JsonNode data) {
        JsonNode schema = schemas.requireSchema(identity.appId());
        JsonNode modelSpec = requireModel(schema, model);
        authorize(schema, modelSpec, identity, "UPDATE");
        AppModelRecord record = requireRecord(identity, model, recordId);
        ThrowUtils.throwIf(expectedVersion > 0
                        && record.getRecordVersion() != expectedVersion,
                ErrorCode.OPERATION_ERROR, "VERSION_CONFLICT：记录已被其他操作更新");
        authorizeOwnership(schema, modelSpec, identity, record.getOwnerId(), "UPDATE");
        ObjectNode value = requireObject(data);
        value.put("id", recordId);
        validateFields(modelSpec, value);
        record.setDataJson(write(value));
        record.setRecordVersion(record.getRecordVersion() + 1);
        record.setUpdateTime(LocalDateTime.now());
        records.update(record);
        return value;
    }

    public boolean delete(AppRuntimeIdentity identity, String model, String recordId) {
        JsonNode schema = schemas.requireSchema(identity.appId());
        JsonNode modelSpec = requireModel(schema, model);
        authorize(schema, modelSpec, identity, "DELETE");
        AppModelRecord record = requireRecord(identity, model, recordId);
        authorizeOwnership(schema, modelSpec, identity, record.getOwnerId(), "DELETE");
        record.setIsDelete(1);
        record.setUpdateTime(LocalDateTime.now());
        records.update(record);
        return true;
    }

    private AppModelRecord requireRecord(AppRuntimeIdentity identity, String model,
                                         String recordId) {
        AppModelRecord record = records.selectOneByQuery(QueryWrapper.create()
                .eq("workspaceId", identity.workspaceId())
                .eq("appId", identity.appId())
                .eq("modelName", model)
                .eq("recordId", recordId)
                .eq("isDelete", 0)
                .limit(1));
        ThrowUtils.throwIf(record == null, ErrorCode.NOT_FOUND_ERROR, "数据记录不存在");
        return record;
    }

    private JsonNode requireModel(JsonNode schema, String model) {
        ThrowUtils.throwIf(StrUtil.isBlank(model) || !model.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"),
                ErrorCode.PARAMS_ERROR, "模型名不合法");
        for (JsonNode candidate : schema.path("models")) {
            if (model.equalsIgnoreCase(candidate.path("name").asText())) {
                return candidate;
            }
        }
        ThrowUtils.throwIf(true, ErrorCode.NOT_FOUND_ERROR, "模型未在 Schema 中声明");
        return null;
    }

    private void authorize(JsonNode schema, JsonNode model,
                           AppRuntimeIdentity identity, String action) {
        if ("OWNER".equals(identity.role())) {
            return;
        }
        String modelName = model.path("name").asText();
        for (JsonNode rule : schema.path("permissions")) {
            if (!identity.role().equalsIgnoreCase(rule.path("role").asText())) {
                continue;
            }
            String ruleModel = rule.path("model").asText();
            if (!"*".equals(ruleModel) && !modelName.equalsIgnoreCase(ruleModel)) {
                continue;
            }
            for (JsonNode allowed : rule.path("actions")) {
                if (action.equalsIgnoreCase(allowed.asText())) {
                    return;
                }
            }
        }
        ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR,
                "当前角色无权执行 " + action + " " + modelName);
    }

    private void authorizeOwnership(JsonNode schema, JsonNode model, AppRuntimeIdentity identity,
                                    String ownerId, String action) {
        if ("OWNER".equals(identity.role()) || "ADMIN".equals(identity.role())) {
            return;
        }
        for (JsonNode actionSpec : schema.path("apiActions")) {
            if (!model.path("name").asText().equalsIgnoreCase(
                    actionSpec.path("model").asText())) {
                continue;
            }
            if (action.equalsIgnoreCase(actionSpec.path("action").asText())
                    && "OWNER".equalsIgnoreCase(actionSpec.path("ownership").asText())
                    && !identity.actorId().equals(ownerId)) {
                ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR, "只能操作自己创建的数据");
            }
        }
    }

    private void validateFields(JsonNode model, ObjectNode data) {
        for (JsonNode field : model.path("fields")) {
            String name = field.path("name").asText();
            if (field.path("required").asBoolean(false) && !data.hasNonNull(name)) {
                ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR,
                        "缺少必填字段：" + name);
            }
            JsonNode value = data.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            String type = field.path("type").asText("string").toLowerCase(Locale.ROOT);
            boolean valid = switch (type) {
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                case "json" -> value.isContainerNode();
                case "date", "datetime", "reference", "string" -> value.isTextual();
                default -> false;
            };
            ThrowUtils.throwIf(!valid, ErrorCode.PARAMS_ERROR,
                    "字段类型不匹配：" + name + " 应为 " + type);
        }
    }

    private ObjectNode requireObject(JsonNode data) {
        ThrowUtils.throwIf(data == null || !data.isObject(),
                ErrorCode.PARAMS_ERROR, "数据必须是 JSON 对象");
        return ((ObjectNode) data).deepCopy();
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("应用数据损坏", e);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化应用数据失败", e);
        }
    }
}
