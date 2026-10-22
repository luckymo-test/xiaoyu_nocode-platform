package com.mofanyi.job;

import com.mofanyi.entity.GenerationOutbox;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationOutboxMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MySQL Outbox 队列。与 Job 创建同事务写入，避免“任务已创建但消息丢失”。
 */
@Component
@ConditionalOnProperty(name = "codegen.jobs.queue", havingValue = "db", matchIfMissing = true)
public class DatabaseGenerationJobQueue implements GenerationJobQueue {

    private final GenerationOutboxMapper outboxMapper;

    public DatabaseGenerationJobQueue(GenerationOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Override
    public List<Delivery> pollReady(int limit) {
        ThrowUtils.throwIf(limit <= 0, ErrorCode.PARAMS_ERROR);
        outboxMapper.resetStaleDispatching();
        return outboxMapper.selectListByQuery(QueryWrapper.create()
                        .eq("status", "PENDING")
                        .le("nextAttemptAt", LocalDateTime.now())
                        .orderBy("nextAttemptAt", true)
                        .limit(Math.min(limit, 100)))
                .stream()
                .filter(item -> outboxMapper.tryClaim(item.getId()) == 1)
                .map(item -> new Delivery(String.valueOf(item.getId()), item.getId(), item.getJobId()))
                .toList();
    }

    @Override
    public void acknowledge(Delivery delivery) {
        ThrowUtils.throwIf(delivery == null, ErrorCode.PARAMS_ERROR);
        outboxMapper.markStatus(delivery.outboxId(), "PUBLISHED", 0);
    }

    @Override
    public void retry(Delivery delivery, int delaySeconds) {
        ThrowUtils.throwIf(delivery == null, ErrorCode.PARAMS_ERROR);
        outboxMapper.markStatus(delivery.outboxId(), "PENDING", Math.max(1, delaySeconds));
    }

    @Override
    public void enqueue(Long jobId) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        LocalDateTime now = LocalDateTime.now();
        GenerationOutbox outbox = GenerationOutbox.builder()
                .jobId(jobId)
                .eventType("DISPATCH")
                .payload(String.valueOf(jobId))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createTime(now)
                .updateTime(now)
                .build();
        ThrowUtils.throwIf(outboxMapper.insertSelective(outbox) == 0, ErrorCode.OPERATION_ERROR);
    }
}
