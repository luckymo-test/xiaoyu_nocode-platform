package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelChunk;
import com.mofanyi.ai.model.ModelCircuitBreaker;
import com.mofanyi.ai.model.ModelConcurrencyLimiter;
import com.mofanyi.ai.model.ModelDescriptor;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelProperties;
import com.mofanyi.ai.model.ModelRegistry;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DefaultModelGateway implements ModelGateway {

    private final ModelRegistry registry;
    private final List<ModelAdapter> adapters;
    private final ModelProperties properties;
    private final ModelCircuitBreaker circuitBreaker;
    private final ModelConcurrencyLimiter concurrencyLimiter;

    public DefaultModelGateway(ModelRegistry registry,
                               List<ModelAdapter> adapters,
                               ModelProperties properties,
                               ModelCircuitBreaker circuitBreaker,
                               ModelConcurrencyLimiter concurrencyLimiter) {
        this.registry = registry;
        this.adapters = List.copyOf(adapters);
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        RuntimeException lastError = null;
        for (ModelDescriptor descriptor : candidates(request)) {
            if (!circuitBreaker.allow(descriptor.id(), properties)) {
                lastError = new IllegalStateException("模型熔断中：" + descriptor.id());
                continue;
            }
            if (!concurrencyLimiter.tryAcquire(descriptor.id(), properties)) {
                lastError = new IllegalStateException("模型并发已满：" + descriptor.id());
                continue;
            }
            try {
                ModelResponse response = adapter(descriptor).chat(descriptor, request);
                circuitBreaker.success(descriptor.id());
                return response;
            } catch (RuntimeException e) {
                lastError = e;
                circuitBreaker.failure(descriptor.id(), properties);
                if (!properties.getFallback().isEnabled()) {
                    throw e;
                }
            } finally {
                concurrencyLimiter.release(descriptor.id(), properties);
            }
        }
        throw new IllegalStateException("所有候选模型调用失败", lastError);
    }

    @Override
    public Flux<ModelChunk> stream(ModelRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        List<ModelDescriptor> candidates = candidates(request);
        return stream(candidates, 0, request);
    }

    private Flux<ModelChunk> stream(List<ModelDescriptor> candidates,
                                    int index,
                                    ModelRequest request) {
        if (index >= candidates.size()) {
            return Flux.error(new IllegalStateException("所有候选模型流式调用失败"));
        }
        ModelDescriptor descriptor = candidates.get(index);
        if (!circuitBreaker.allow(descriptor.id(), properties)) {
            return properties.getFallback().isEnabled()
                    ? stream(candidates, index + 1, request)
                    : Flux.error(new IllegalStateException("模型熔断中：" + descriptor.id()));
        }
        if (!concurrencyLimiter.tryAcquire(descriptor.id(), properties)) {
            return properties.getFallback().isEnabled()
                    ? stream(candidates, index + 1, request)
                    : Flux.error(new IllegalStateException(
                    "模型并发已满：" + descriptor.id()));
        }
        AtomicBoolean emitted = new AtomicBoolean();
        Flux<ModelChunk> source;
        try {
            source = adapter(descriptor).stream(descriptor, request);
        } catch (RuntimeException e) {
            circuitBreaker.failure(descriptor.id(), properties);
            concurrencyLimiter.release(descriptor.id(), properties);
            return properties.getFallback().isEnabled()
                    ? stream(candidates, index + 1, request)
                    : Flux.error(e);
        }
        return source
                .doOnNext(chunk -> emitted.set(true))
                .doOnComplete(() -> circuitBreaker.success(descriptor.id()))
                .onErrorResume(error -> properties.getFallback().isEnabled()
                        && !emitted.get()
                        ? streamAfterFailure(candidates, index, request, descriptor.id())
                        : failStream(descriptor.id(), error))
                .doFinally(signal -> concurrencyLimiter.release(descriptor.id(), properties));
    }

    private Flux<ModelChunk> streamAfterFailure(List<ModelDescriptor> candidates,
                                                int index,
                                                ModelRequest request,
                                                String modelId) {
        circuitBreaker.failure(modelId, properties);
        return stream(candidates, index + 1, request);
    }

    private Flux<ModelChunk> failStream(String modelId, Throwable error) {
        circuitBreaker.failure(modelId, properties);
        return Flux.error(error);
    }

    private List<ModelDescriptor> candidates(ModelRequest request) {
        ThrowUtils.throwIf(!properties.getGateway().isEnabled(),
                ErrorCode.OPERATION_ERROR, "ModelGateway 未启用");
        if (request.preferredModelId() != null && !request.preferredModelId().isBlank()) {
            ModelDescriptor preferred = registry.require(request.preferredModelId());
            registry.validateCandidate(preferred, registry.requiredCapabilities(
                            request.task(), request.requiredCapabilities()),
                    request.estimatedInputTokens(), request.requestedOutputTokens());
            return List.of(preferred);
        }
        if (!properties.getRouting().isEnabled()) {
            ModelDescriptor fallback = registry.require(properties.getDefaultModel());
            registry.validateCandidate(fallback, registry.requiredCapabilities(
                            request.task(), request.requiredCapabilities()),
                    request.estimatedInputTokens(), request.requestedOutputTokens());
            return List.of(fallback);
        }
        return registry.routeCandidates(
                request.task(),
                request.requiredCapabilities(),
                request.estimatedInputTokens(),
                request.requestedOutputTokens());
    }

    private ModelAdapter adapter(ModelDescriptor descriptor) {
        return adapters.stream()
                .filter(candidate -> candidate.supports(descriptor.provider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "没有模型适配器支持 provider=" + descriptor.provider()));
    }
}
