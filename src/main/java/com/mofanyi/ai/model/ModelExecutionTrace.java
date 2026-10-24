package com.mofanyi.ai.model;

import com.mofanyi.ai.reasoning.ReasoningProfile;
import com.mofanyi.core.AiCallContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单 Job 的模型执行快照，供 Revision Manifest 记录“实际用了什么”，而不是只记录当前配置。
 *
 * <p>调用明细同时异步落 {@code ai_call_log}；这里保留进程内轻量副本，避免 Revision
 * 发布时与异步落库竞态。Job 没有生成 Revision 时，该副本会在下一次进程重启后自然消失。
 */
@Component
public class ModelExecutionTrace {

    private static final int MAX_CALLS_PER_JOB = 256;
    private final ConcurrentHashMap<Long, Trace> traces = new ConcurrentHashMap<>();

    public void recordProfile(Long jobId, String reasoningLevel, ReasoningProfile profile) {
        if (jobId == null || profile == null) {
            return;
        }
        String level = reasoningLevel == null || reasoningLevel.isBlank()
                ? "standard" : reasoningLevel;
        trace(jobId).profiles.put(level, Map.of(
                "thinkingEnabled", profile.thinkingEnabled(),
                "effort", profile.effort() == null ? "" : profile.effort(),
                "maxSteps", profile.maxSteps(),
                "maxLoopTokens", profile.maxLoopTokens(),
                "repairRounds", profile.repairRounds(),
                "maxOutputTokens", profile.maxOutputTokens(),
                "legacyModelPreference", profile.model()));
    }

    public void recordTools(Long jobId, List<ToolCallback> callbacks) {
        if (jobId == null || callbacks == null || callbacks.isEmpty()) {
            return;
        }
        Map<String, String> schemas = new TreeMap<>();
        for (ToolCallback callback : callbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            ToolDefinition definition = callback.getToolDefinition();
            String schema = definition.inputSchema() == null ? "" : definition.inputSchema();
            String description = definition.description() == null ? "" : definition.description();
            schemas.put(definition.name(), version(definition.name() + "\n"
                    + description + "\n" + schema));
        }
        Trace trace = trace(jobId);
        trace.toolSchemas.clear();
        trace.toolSchemas.putAll(schemas);
        trace.toolSchemaVersion = version(String.join("\n", schemas.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList()));
    }

    public void recordCall(AiCallContext context, String task, String status,
                           String provider, String modelId, String modelName,
                           String reasoningLevel, long inputTokens, long outputTokens,
                           long durationMs, String finishReason) {
        if (context == null || context.jobId() == null) {
            return;
        }
        Trace trace = trace(context.jobId());
        if (trace.calls.size() >= MAX_CALLS_PER_JOB) {
            return;
        }
        trace.calls.add(new CallSnapshot(
                task,
                status,
                provider,
                modelId,
                modelName,
                reasoningLevel,
                Math.max(0, inputTokens),
                Math.max(0, outputTokens),
                Math.max(0, durationMs),
                finishReason));
    }

    /**
     * 生成可写入 Manifest 的 JSON 友好快照。包含配置版本和本 Job 的实际调用，不含 Prompt 正文。
     */
    public Map<String, Object> snapshot(Long jobId, ModelRegistry registry) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("routing", registry == null ? Map.of() : registry.configurationSnapshot());
        Trace trace = jobId == null ? null : traces.get(jobId);
        snapshot.put("reasoningProfiles",
                trace == null ? Map.of() : new TreeMap<>(trace.profiles));
        snapshot.put("toolSchemaVersion",
                trace == null ? version("") : trace.toolSchemaVersion);
        snapshot.put("toolSchemas",
                trace == null ? Map.of() : new TreeMap<>(trace.toolSchemas));
        snapshot.put("calls",
                trace == null ? List.of() : new ArrayList<>(trace.calls));
        return snapshot;
    }

    public void clear(Long jobId) {
        if (jobId != null) {
            traces.remove(jobId);
        }
    }

    private Trace trace(Long jobId) {
        return traces.computeIfAbsent(jobId, ignored -> new Trace());
    }

    public static String version(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算版本指纹失败", e);
        }
    }

    private static final class Trace {
        private final Map<String, Map<String, Object>> profiles = new ConcurrentHashMap<>();
        private final Map<String, String> toolSchemas = new ConcurrentHashMap<>();
        private final List<CallSnapshot> calls = new CopyOnWriteArrayList<>();
        private volatile String toolSchemaVersion = version("");
    }

    public record CallSnapshot(
            String task,
            String status,
            String provider,
            String modelId,
            String modelName,
            String reasoningLevel,
            long inputTokens,
            long outputTokens,
            long durationMs,
            String finishReason) {
    }
}
