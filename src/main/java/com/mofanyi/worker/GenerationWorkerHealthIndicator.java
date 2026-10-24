package com.mofanyi.worker;

import com.mofanyi.job.GenerationExecutionCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = GenerationExecutionCondition.MODE_PROPERTY,
        havingValue = GenerationExecutionCondition.WORKER)
public class GenerationWorkerHealthIndicator implements HealthIndicator {

    @Value("${codegen.jobs.worker-id:${HOSTNAME:local-worker}}")
    private String workerId;

    @Value("${codegen.generation-worker.consumer-concurrency:2}")
    private int consumerConcurrency;

    @Value("${codegen.generation-worker.lease-seconds:120}")
    private int leaseSeconds;

    @Override
    public Health health() {
        return Health.up()
                .withDetail("mode", GenerationExecutionCondition.WORKER)
                .withDetail("workerId", workerId)
                .withDetail("consumerConcurrency", consumerConcurrency)
                .withDetail("leaseSeconds", leaseSeconds)
                .build();
    }
}
