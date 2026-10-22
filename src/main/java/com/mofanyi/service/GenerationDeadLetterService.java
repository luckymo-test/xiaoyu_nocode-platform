package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.GenerationDeadLetter;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationDeadLetterMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class GenerationDeadLetterService {

    private final GenerationDeadLetterMapper mapper;

    public GenerationDeadLetterService(GenerationDeadLetterMapper mapper) {
        this.mapper = mapper;
    }

    public void record(GenerationJob job, String errorType, String errorMessage) {
        ThrowUtils.throwIf(job == null || job.getId() == null, ErrorCode.PARAMS_ERROR);
        LocalDateTime now = LocalDateTime.now();
        GenerationDeadLetter letter = GenerationDeadLetter.builder()
                .jobId(job.getId())
                .workspaceId(job.getWorkspaceId())
                .appId(job.getAppId())
                .requestedBy(job.getRequestedBy())
                .attempt(Math.max(1, job.getAttempt() == null ? 1 : job.getAttempt()))
                .errorType(StrUtil.maxLength(errorType, 64))
                .errorMessage(StrUtil.maxLength(errorMessage, 4000))
                .payload(job.getRequestPayload())
                .status("PENDING")
                .createTime(now)
                .updateTime(now)
                .build();
        try {
            mapper.insertSelective(letter);
        } catch (DuplicateKeyException e) {
            log.debug("[job] 死信已存在，jobId={}, attempt={}", job.getId(), letter.getAttempt());
        }
    }
}
