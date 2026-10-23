package com.mofanyi.ai.model;

import reactor.core.publisher.Flux;

public interface ModelGateway {

    ModelResponse chat(ModelRequest request);

    Flux<ModelChunk> stream(ModelRequest request);
}
