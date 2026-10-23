package com.mofanyi.ai.model.gateway;

import com.mofanyi.ai.model.ModelChunk;
import com.mofanyi.ai.model.ModelDescriptor;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import reactor.core.publisher.Flux;

public interface ModelAdapter {

    boolean supports(String provider);

    ModelResponse chat(ModelDescriptor descriptor, ModelRequest request);

    Flux<ModelChunk> stream(ModelDescriptor descriptor, ModelRequest request);
}
