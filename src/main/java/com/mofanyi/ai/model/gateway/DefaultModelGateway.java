package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelChunk;
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

    public DefaultModelGateway(ModelRegistry registry,
                               List<ModelAdapter> adapters,
                               ModelProperties properties) {
        this.registry = registry;
        this.adapters = List.copyOf(adapters);
        this.properties = properties;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        RuntimeException lastError = null;
        for (ModelDescriptor descriptor : candidates(request)) {
            try {
                return adapter(descriptor).chat(descriptor, request);
            } catch (RuntimeException e) {
                lastError = e;
                if (!properties.getFallback().isEnabled()) {
                    throw e;
                }
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
        AtomicBoolean emitted = new AtomicBoolean();
        return adapter(descriptor).stream(descriptor, request)
                .doOnNext(chunk -> emitted.set(true))
                .onErrorResume(error -> properties.getFallback().isEnabled()
                        && !emitted.get()
                        ? stream(candidates, index + 1, request)
                        : Flux.error(error));
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
