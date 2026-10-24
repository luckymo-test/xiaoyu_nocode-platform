package com.mofanyi.ai.model;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

@Component
public class ModelConcurrencyLimiter {

    private final ConcurrentMap<String, Semaphore> permits = new ConcurrentHashMap<>();

    public boolean tryAcquire(String modelId, ModelProperties properties) {
        if (!properties.getResilience().isEnabled()) {
            return true;
        }
        int configured = Math.max(properties.getResilience().getMaxConcurrentCalls(), 1);
        return permits.computeIfAbsent(modelId, ignored -> new Semaphore(configured))
                .tryAcquire();
    }

    public void release(String modelId, ModelProperties properties) {
        if (!properties.getResilience().isEnabled()) {
            return;
        }
        Semaphore semaphore = permits.get(modelId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    public void reset() {
        permits.clear();
    }
}
