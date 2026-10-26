package com.mofanyi.workflow;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.AgentStep;
import com.mofanyi.entity.AgentToolCall;
import com.mofanyi.mapper.AgentStepMapper;
import com.mofanyi.mapper.AgentToolCallMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Persists every Coder-side tool invocation. Large binary/text arguments are
 * reduced to hashes, while file paths and before/after hashes stay queryable.
 */
@Component
public class AgentToolCallRecorder {

    private static final int MAX_ARGUMENT_CHARS = 8_000;
    private static final int MAX_RESULT_CHARS = 2_000;

    private final AgentToolCallMapper callMapper;
    private final AgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;
    private final CodeFileWriteService files;

    public AgentToolCallRecorder(AgentToolCallMapper callMapper,
                                 AgentStepMapper stepMapper,
                                 ObjectMapper objectMapper,
                                 CodeFileWriteService files) {
        this.callMapper = callMapper;
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
        this.files = files;
    }

    public AgentToolCall start(Long jobId, String callId, String toolName, String arguments,
                               CodeGenTypeEnum type, Long appId) {
        if (jobId == null) {
            return null;
        }
        String path = readPath(arguments);
        AgentStep step = stepMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .eq("status", "RUNNING")
                .orderBy("createTime", true)
                .limit(1));
        LocalDateTime now = LocalDateTime.now();
        AgentToolCall call = AgentToolCall.builder()
                .jobId(jobId)
                .stepId(step == null ? null : step.getId())
                .toolCallId(callId)
                .toolName(toolName)
                .argumentsJson(sanitize(arguments))
                .status("RUNNING")
                .filePath(path)
                .beforeSha256(fileHash(type, appId, path))
                .createTime(now)
                .updateTime(now)
                .build();
        callMapper.insertSelective(call);
        return call;
    }

    public void success(AgentToolCall call, String result, long durationMs,
                        CodeGenTypeEnum type, Long appId) {
        if (call == null) {
            return;
        }
        call.setStatus("SUCCEEDED");
        call.setResultSummary(StrUtil.maxLength(result, MAX_RESULT_CHARS));
        call.setDurationMs(durationMs);
        call.setAfterSha256(fileHash(type, appId, call.getFilePath()));
        call.setUpdateTime(LocalDateTime.now());
        callMapper.update(call);
    }

    public void failure(AgentToolCall call, RuntimeException error, long durationMs) {
        if (call == null) {
            return;
        }
        call.setStatus("FAILED");
        call.setDurationMs(durationMs);
        call.setErrorMessage(StrUtil.maxLength(error.getMessage(), 2_000));
        call.setUpdateTime(LocalDateTime.now());
        callMapper.update(call);
    }

    private String fileHash(CodeGenTypeEnum type, Long appId, String path) {
        if (type == null || appId == null || StrUtil.isBlank(path)) {
            return null;
        }
        try {
            String content = files.readFile(files.getProjectDir(type, appId), path);
            if (content == null) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readPath(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            String path = node.path("path").asText("");
            return StrUtil.maxLength(path, 1_024);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sanitize(String arguments) {
        if (arguments == null) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (parsed instanceof ObjectNode object) {
                for (String field : new String[]{"content", "text"}) {
                    if (object.has(field)) {
                        object.put(field, "sha256:" + sha256(object.path(field).asText("")));
                    }
                }
            }
            return StrUtil.maxLength(objectMapper.writeValueAsString(parsed), MAX_ARGUMENT_CHARS);
        } catch (Exception ignored) {
            return StrUtil.maxLength(arguments, MAX_ARGUMENT_CHARS);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
