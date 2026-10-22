package com.mofanyi.job;

import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.model.enums.GenerationJobStatusEnum;
import com.mofanyi.service.GenerationJobService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Outbox Dispatcher：轮询持久队列、提交 Runner，并回收过期租约。
 */
@Slf4j
@Component
public class GenerationJobDispatcher {

    private final GenerationJobQueue queue;
    private final GenerationJobRunner runner;
    private final GenerationJobService jobService;
    private final GenerationJobMapper jobMapper;
    private final ExecutorService jobExecutionExecutor;
    private final GenerationJobMetrics metrics;

    public GenerationJobDispatcher(GenerationJobQueue queue,
                                   GenerationJobRunner runner,
                                   GenerationJobService jobService,
                                   GenerationJobMapper jobMapper,
                                   GenerationJobMetrics metrics,
                                   @Qualifier("jobExecutionExecutor")
                                   ExecutorService jobExecutionExecutor) {
        this.queue = queue;
        this.runner = runner;
        this.jobService = jobService;
        this.jobMapper = jobMapper;
        this.metrics = metrics;
        this.jobExecutionExecutor = jobExecutionExecutor;
    }

    @Scheduled(fixedDelayString = "${codegen.jobs.dispatch-delay-ms:1000}")
    public void dispatch() {
        metrics.refresh();
        List<GenerationJobQueue.Delivery> deliveries = queue.pollReady(20);
        for (GenerationJobQueue.Delivery delivery : deliveries) {
            try {
                jobExecutionExecutor.execute(() -> runDelivery(delivery));
                metrics.dispatched();
            } catch (RuntimeException e) {
                queue.retry(delivery, 10);
                log.warn("[job] 提交执行器失败，jobId={}", delivery.jobId(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${codegen.jobs.recovery-delay-ms:30000}")
    public void recoverExpiredLeases() {
        metrics.refresh();
        List<GenerationJob> expired = jobMapper.selectListByQuery(QueryWrapper.create()
                .eq("status", GenerationJobStatusEnum.RUNNING.getValue())
                .lt("leaseExpiresAt", LocalDateTime.now())
                .limit(50));
        for (GenerationJob job : expired) {
            int updated = jobMapper.recoverExpired(job.getId(), job.getLeaseOwner(), job.getFencingToken());
            if (updated == 1) {
                queue.enqueue(job.getId());
                log.warn("[job] 回收过期租约并重新入队，jobId={}, oldOwner={}",
                        job.getId(), job.getLeaseOwner());
            }
        }
        List<GenerationJob> cancelWithoutLease = jobMapper.selectListByQuery(QueryWrapper.create()
                .eq("status", GenerationJobStatusEnum.CANCEL_REQUESTED.getValue())
                .isNull("leaseOwner")
                .limit(50));
        List<GenerationJob> expiredCancel = jobMapper.selectListByQuery(QueryWrapper.create()
                .eq("status", GenerationJobStatusEnum.CANCEL_REQUESTED.getValue())
                .lt("leaseExpiresAt", LocalDateTime.now())
                .limit(50));
        for (GenerationJob job : concat(cancelWithoutLease, expiredCancel)) {
            jobMapper.finishCancelRequested(job.getId());
        }
    }

    private void runDelivery(GenerationJobQueue.Delivery delivery) {
        try {
            GenerationJob job = jobMapper.selectOneById(delivery.jobId());
            if (job == null || GenerationJobStatusEnum.isTerminal(job.getStatus())) {
                queue.acknowledge(delivery);
                return;
            }
            runner.run(job.getId());
            queue.acknowledge(delivery);
        } catch (RuntimeException e) {
            GenerationJob job = jobMapper.selectOneById(delivery.jobId());
            if (job != null && GenerationJobStatusEnum.RUNNING.getValue().equals(job.getStatus())) {
                jobService.finish(job, GenerationJobStatusEnum.FAILED.getValue(),
                        "FAILED", e.getClass().getSimpleName(), e.getMessage());
            }
            queue.retry(delivery, 10);
            log.error("[job] Runner 执行失败，jobId={}", delivery.jobId(), e);
        }
    }

    private List<GenerationJob> concat(List<GenerationJob> first, List<GenerationJob> second) {
        if (second.isEmpty()) {
            return first;
        }
        java.util.LinkedHashMap<Long, GenerationJob> unique = new java.util.LinkedHashMap<>();
        first.forEach(job -> unique.put(job.getId(), job));
        second.forEach(job -> unique.put(job.getId(), job));
        return List.copyOf(unique.values());
    }
}
