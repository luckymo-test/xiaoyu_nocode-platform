package com.mofanyi.job;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.GenerationOutbox;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationOutboxMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Redis Streams 投递实现。MySQL Outbox 仍是真相源，Relay 负责把 PENDING 行转成 Stream 消息。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codegen.jobs.queue", havingValue = "redis")
public class RedisGenerationJobQueue implements GenerationJobQueue {

    private final StringRedisTemplate redisTemplate;
    private final GenerationOutboxMapper outboxMapper;

    @Value("${codegen.jobs.redis.stream:codegen:generation-jobs}")
    private String stream;

    @Value("${codegen.jobs.redis.group:generation-workers}")
    private String group;

    @Value("${codegen.jobs.worker-id:${HOSTNAME:local-worker}}")
    private String consumer;

    public RedisGenerationJobQueue(StringRedisTemplate redisTemplate,
                                   GenerationOutboxMapper outboxMapper) {
        this.redisTemplate = redisTemplate;
        this.outboxMapper = outboxMapper;
    }

    @Override
    public List<Delivery> pollReady(int limit) {
        ThrowUtils.throwIf(limit <= 0, ErrorCode.PARAMS_ERROR);
        if (!ensureGroup()) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(Math.min(limit, 100))
                        .block(Duration.ofMillis(500)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(record -> toDelivery(record))
                .filter(delivery -> delivery != null)
                .toList();
    }

    @Override
    public void acknowledge(Delivery delivery) {
        ThrowUtils.throwIf(delivery == null, ErrorCode.PARAMS_ERROR);
        redisTemplate.opsForStream().acknowledge(stream, group, RecordId.of(delivery.deliveryId()));
        if (delivery.outboxId() != null) {
            outboxMapper.markStatus(delivery.outboxId(), "PUBLISHED", 0);
        }
    }

    @Override
    public void retry(Delivery delivery, int delaySeconds) {
        ThrowUtils.throwIf(delivery == null, ErrorCode.PARAMS_ERROR);
        acknowledge(delivery);
        schedule(delivery.jobId(), delaySeconds);
    }

    @Override
    public void enqueue(Long jobId) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        redisTemplate.opsForStream().add(stream, Map.of("jobId", String.valueOf(jobId)));
    }

    @Override
    public void schedule(Long jobId, int delaySeconds) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        LocalDateTime now = LocalDateTime.now();
        GenerationOutbox outbox = GenerationOutbox.builder()
                .jobId(jobId)
                .eventType("DISPATCH")
                .payload(String.valueOf(jobId))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now.plusSeconds(Math.max(0, delaySeconds)))
                .createTime(now)
                .updateTime(now)
                .build();
        ThrowUtils.throwIf(outboxMapper.insertSelective(outbox) == 0,
                ErrorCode.OPERATION_ERROR);
    }

    /**
     * Transactional Outbox Relay：只有 XADD 成功后才标记 PUBLISHED。
     */
    @Scheduled(fixedDelayString = "${codegen.jobs.dispatch-delay-ms:1000}")
    public void relayOutbox() {
        List<GenerationOutbox> pending = outboxMapper.selectListByQuery(QueryWrapper.create()
                .eq("status", "PENDING")
                .le("nextAttemptAt", LocalDateTime.now())
                .orderBy("nextAttemptAt", true)
                .limit(50));
        for (GenerationOutbox outbox : pending) {
            if (outboxMapper.tryClaim(outbox.getId()) != 1) {
                continue;
            }
            try {
                enqueue(outbox.getJobId());
                outboxMapper.markStatus(outbox.getId(), "PUBLISHED", 0);
            } catch (RuntimeException e) {
                outboxMapper.markStatus(outbox.getId(), "PENDING", 10);
                log.warn("[job] Outbox 投递 Redis Streams 失败，outboxId={}", outbox.getId(), e);
            }
        }
    }

    private Delivery toDelivery(MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get("jobId");
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            redisTemplate.opsForStream().acknowledge(stream, group, record.getId());
            return null;
        }
        try {
            return new Delivery(record.getId().getValue(), null,
                    Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException e) {
            redisTemplate.opsForStream().acknowledge(stream, group, record.getId());
            return null;
        }
    }

    private boolean ensureGroup() {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(stream))) {
            return false;
        }
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
            return true;
        } catch (RuntimeException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
            return true;
        }
    }
}
