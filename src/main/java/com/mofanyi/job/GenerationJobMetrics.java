package com.mofanyi.job;

import com.mofanyi.mapper.GenerationDeadLetterMapper;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mybatisflex.core.query.QueryWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 只暴露低基数队列指标；appId/userId 等高基数维度仍在 MySQL 明细。
 */
@Component
public class GenerationJobMetrics {

    private final GenerationJobMapper jobMapper;
    private final GenerationDeadLetterMapper deadLetterMapper;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final AtomicLong waitingRetry = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    private final AtomicLong lastRefreshAt = new AtomicLong();
    private final Counter dispatched;
    private final Counter retries;
    private final Counter failures;

    public GenerationJobMetrics(GenerationJobMapper jobMapper,
                                GenerationDeadLetterMapper deadLetterMapper,
                                MeterRegistry registry) {
        this.jobMapper = jobMapper;
        this.deadLetterMapper = deadLetterMapper;
        Gauge.builder("generation_queue_jobs", queued, AtomicLong::get)
                .tag("status", "queued").register(registry);
        Gauge.builder("generation_queue_jobs", running, AtomicLong::get)
                .tag("status", "running").register(registry);
        Gauge.builder("generation_queue_jobs", waitingRetry, AtomicLong::get)
                .tag("status", "waiting_retry").register(registry);
        Gauge.builder("generation_dead_letter_jobs", deadLetters, AtomicLong::get)
                .register(registry);
        dispatched = Counter.builder("generation_jobs_dispatched_total").register(registry);
        retries = Counter.builder("generation_job_retries_total").register(registry);
        failures = Counter.builder("generation_job_failures_total").register(registry);
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        long previous = lastRefreshAt.get();
        if (now - previous < 5000 || !lastRefreshAt.compareAndSet(previous, now)) {
            return;
        }
        queued.set(count("QUEUED"));
        running.set(count("RUNNING"));
        waitingRetry.set(count("WAITING_RETRY"));
        deadLetters.set(deadLetterMapper.selectCountByQuery(QueryWrapper.create()
                .eq("status", "PENDING")));
    }

    public void dispatched() {
        dispatched.increment();
    }

    public void retried() {
        retries.increment();
    }

    public void failed() {
        failures.increment();
    }

    private long count(String status) {
        return jobMapper.selectCountByQuery(QueryWrapper.create().eq("status", status));
    }
}
