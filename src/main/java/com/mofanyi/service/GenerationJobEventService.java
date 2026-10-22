package com.mofanyi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mofanyi.entity.GenerationJobEvent;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationJobEventMapper;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Job 事件写入与按 seq 读取。事件 payload 统一保存为 JSON 文本。
 */
@Service
public class GenerationJobEventService {

    public static final String VISIBILITY_USER = "user";

    private final GenerationJobMapper jobMapper;
    private final GenerationJobEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public GenerationJobEventService(GenerationJobMapper jobMapper,
                                     GenerationJobEventMapper eventMapper,
                                     ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobEvent append(Long jobId, String eventType, String stage, Object payload) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(eventType == null || eventType.isBlank(), ErrorCode.PARAMS_ERROR);
        Long current = jobMapper.lockNextEventSeq(jobId);
        ThrowUtils.throwIf(current == null, ErrorCode.NOT_FOUND_ERROR, "生成任务不存在");
        long seq = current + 1;
        ThrowUtils.throwIf(jobMapper.updateNextEventSeq(jobId, seq, stage) != 1,
                ErrorCode.OPERATION_ERROR, "分配任务事件序号失败");
        GenerationJobEvent event = GenerationJobEvent.builder()
                .jobId(jobId)
                .seq(seq)
                .eventType(eventType)
                .stage(stage)
                .visibility(VISIBILITY_USER)
                .payload(serialize(payload))
                .createTime(LocalDateTime.now())
                .build();
        ThrowUtils.throwIf(eventMapper.insertSelective(event) == 0, ErrorCode.OPERATION_ERROR);
        return event;
    }

    /**
     * 只允许持有当前 fencingToken 的 Runner 写事件，旧 Worker 恢复后不能污染事件流。
     */
    public GenerationJobEvent appendFenced(GenerationJob job, String eventType,
                                           String stage, Object payload) {
        ThrowUtils.throwIf(job == null || job.getId() == null || job.getFencingToken() == null,
                ErrorCode.PARAMS_ERROR);
        long count = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("id", job.getId())
                .eq("fencingToken", job.getFencingToken())
                .in("status", "RUNNING", "CANCEL_REQUESTED"));
        ThrowUtils.throwIf(count == 0, ErrorCode.OPERATION_ERROR,
                "Worker fencingToken 已失效");
        return append(job.getId(), eventType, stage, payload);
    }

    public List<GenerationJobEvent> listAfter(Long jobId, long afterSeq, int limit) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(afterSeq < 0, ErrorCode.PARAMS_ERROR);
        int size = Math.max(1, Math.min(limit, 500));
        return eventMapper.selectListByQuery(QueryWrapper.create()
                .eq("jobId", jobId)
                .gt("seq", afterSeq)
                .eq("visibility", VISIBILITY_USER)
                .orderBy("seq", true)
                .limit(size));
    }

    public JsonNode readPayload(GenerationJobEvent event) {
        ThrowUtils.throwIf(event == null, ErrorCode.PARAMS_ERROR);
        try {
            if (event.getPayload() == null || event.getPayload().isBlank()) {
                return TextNode.valueOf("");
            }
            return objectMapper.readTree(event.getPayload());
        } catch (Exception ignored) {
            return TextNode.valueOf(event.getPayload());
        }
    }

    private String serialize(Object payload) {
        if (payload == null) {
            return "null";
        }
        try {
            if (payload instanceof String text) {
                String trimmed = text.trim();
                if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                        || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                    return objectMapper.readTree(trimmed).toString();
                }
                return objectMapper.writeValueAsString(text);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(String.valueOf(payload)).toString();
        }
    }
}
