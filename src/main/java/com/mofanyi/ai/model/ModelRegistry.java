package com.mofanyi.ai.model;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public class ModelRegistry {

    private final ModelProperties properties;

    public ModelRegistry(ModelProperties properties) {
        this.properties = properties;
    }

    public ModelDescriptor require(String modelId) {
        ThrowUtils.throwIf(modelId == null || modelId.isBlank(),
                ErrorCode.PARAMS_ERROR, "模型 id 不能为空");
        ModelProperties.Definition definition = properties.getDefinitions().get(modelId);
        ThrowUtils.throwIf(definition == null, ErrorCode.OPERATION_ERROR,
                "模型未注册：" + modelId);
        ThrowUtils.throwIf(!definition.isEnabled(), ErrorCode.OPERATION_ERROR,
                "模型已禁用：" + modelId);
        ThrowUtils.throwIf(definition.getProvider() == null
                        || definition.getModelName() == null,
                ErrorCode.SYSTEM_ERROR, "模型定义不完整：" + modelId);
        return new ModelDescriptor(
                modelId,
                definition.getProvider(),
                definition.getModelName(),
                definition.getContextWindow(),
                definition.getMaxOutputTokens(),
                new LinkedHashSet<>(definition.getSupports()),
                definition.getReasoningStyle(),
                definition.isEnabled());
    }

    public List<ModelDescriptor> routeCandidates(ModelTask task,
                                                 Set<ModelCapability> requiredCapabilities,
                                                 int estimatedInputTokens,
                                                 int requestedOutputTokens) {
        ThrowUtils.throwIf(task == null, ErrorCode.PARAMS_ERROR, "模型任务不能为空");
        String routedId = properties.getRoutes().get(task.configKey());
        ThrowUtils.throwIf(routedId == null || routedId.isBlank(),
                ErrorCode.OPERATION_ERROR, "任务未配置模型路由：" + task.configKey());

        List<String> ids = new ArrayList<>();
        ids.add(routedId);
        if (properties.getFallback().isEnabled()) {
            ids.addAll(properties.getFallbacks().getOrDefault(routedId, List.of()));
        }

        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        if (requiredCapabilities != null) {
            capabilities.addAll(requiredCapabilities);
        }
        capabilities.addAll(inferredCapabilities(task));

        List<ModelDescriptor> candidates = new ArrayList<>();
        List<String> rejectionReasons = new ArrayList<>();
        for (String id : ids) {
            try {
                ModelDescriptor descriptor = require(id);
                validateCandidate(descriptor, capabilities,
                        estimatedInputTokens, requestedOutputTokens);
                candidates.add(descriptor);
            } catch (RuntimeException e) {
                rejectionReasons.add(id + ": " + e.getMessage());
            }
        }
        ThrowUtils.throwIf(candidates.isEmpty(), ErrorCode.OPERATION_ERROR,
                "没有满足任务 " + task.configKey() + " 的模型：" + String.join("; ", rejectionReasons));
        return List.copyOf(candidates);
    }

    public ModelDescriptor route(ModelTask task,
                                 Set<ModelCapability> requiredCapabilities,
                                 int estimatedInputTokens,
                                 int requestedOutputTokens) {
        return routeCandidates(task, requiredCapabilities,
                estimatedInputTokens, requestedOutputTokens).getFirst();
    }

    public void validateCandidate(ModelDescriptor descriptor,
                                  Set<ModelCapability> requiredCapabilities,
                                  int estimatedInputTokens,
                                  int requestedOutputTokens) {
        for (ModelCapability capability : requiredCapabilities) {
            ThrowUtils.throwIf(!descriptor.supports(capability),
                    ErrorCode.OPERATION_ERROR,
                    "模型 " + descriptor.id() + " 不支持 " + capability);
        }
        int outputTokens = Math.min(Math.max(requestedOutputTokens, 1),
                descriptor.maxOutputTokens());
        ThrowUtils.throwIf(estimatedInputTokens <= 0, ErrorCode.PARAMS_ERROR,
                "输入 token 估算必须大于 0");
        ThrowUtils.throwIf(estimatedInputTokens + outputTokens > descriptor.contextWindow(),
                ErrorCode.OPERATION_ERROR,
                "模型 " + descriptor.id() + " 上下文不足：input="
                        + estimatedInputTokens + " output=" + outputTokens
                        + " context=" + descriptor.contextWindow());
    }

    public Set<ModelCapability> requiredCapabilities(
            ModelTask task, Set<ModelCapability> explicitCapabilities) {
        ThrowUtils.throwIf(task == null, ErrorCode.PARAMS_ERROR, "模型任务不能为空");
        Set<ModelCapability> capabilities = new LinkedHashSet<>();
        if (explicitCapabilities != null) {
            capabilities.addAll(explicitCapabilities);
        }
        capabilities.addAll(inferredCapabilities(task));
        return Set.copyOf(capabilities);
    }

    /**
     * 可写入 Revision Manifest 的路由配置快照；version 是配置内容指纹，不包含密钥或 URL。
     */
    public Map<String, Object> configurationSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> definitions = new TreeMap<>();
        for (Map.Entry<String, ModelProperties.Definition> entry
                : properties.getDefinitions().entrySet()) {
            ModelProperties.Definition definition = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", definition.getProvider());
            item.put("modelName", definition.getModelName());
            item.put("contextWindow", definition.getContextWindow());
            item.put("maxOutputTokens", definition.getMaxOutputTokens());
            item.put("capabilities", definition.getSupports().stream()
                    .map(Enum::name).sorted().toList());
            item.put("reasoningStyle", definition.getReasoningStyle());
            item.put("enabled", definition.isEnabled());
            definitions.put(entry.getKey(), item);
        }
        Map<String, String> routes = new TreeMap<>(properties.getRoutes());
        Map<String, List<String>> fallbacks = new TreeMap<>(properties.getFallbacks());
        snapshot.put("routingEnabled", properties.getRouting().isEnabled());
        snapshot.put("fallbackEnabled", properties.getFallback().isEnabled());
        snapshot.put("defaultModel", properties.getDefaultModel());
        snapshot.put("definitions", definitions);
        snapshot.put("routes", routes);
        snapshot.put("fallbacks", fallbacks);
        snapshot.put("version", ModelExecutionTrace.version(
                snapshot.toString()));
        return snapshot;
    }

    private static Set<ModelCapability> inferredCapabilities(ModelTask task) {
        return switch (task) {
            case GENERATE, REPAIR, MODIFY -> Set.of(ModelCapability.TOOLS);
            case VISION_CURATE -> Set.of(ModelCapability.VISION);
            case QUERY_PLAN, COMPRESS -> Set.of(ModelCapability.STRUCTURED_OUTPUT);
            case RESEARCH -> Set.of(ModelCapability.STREAMING);
        };
    }
}
