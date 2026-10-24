package com.mofanyi.ai.model;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ModelCircuitBreaker {

    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    public boolean allow(String modelId, ModelProperties properties) {
        ModelProperties.Resilience config = properties.getResilience();
        if (!config.isEnabled()) {
            return true;
        }
        State state = states.get(modelId);
        return state == null || state.openUntil() <= System.currentTimeMillis();
    }

    public void success(String modelId) {
        states.remove(modelId);
    }

    public void failure(String modelId, ModelProperties properties) {
        ModelProperties.Resilience config = properties.getResilience();
        if (!config.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        states.compute(modelId, (ignored, current) -> {
            int failures = current == null
                    || (current.openUntil() > 0 && current.openUntil() <= now)
                    ? 1 : current.failures() + 1;
            long openUntil = failures >= Math.max(config.getFailureThreshold(), 1)
                    ? now + Math.max(config.getOpenDurationMs(), 1_000L) : 0L;
            return new State(failures, openUntil);
        });
    }

    public void reset() {
        states.clear();
    }

    private record State(int failures, long openUntil) {
    }
}
