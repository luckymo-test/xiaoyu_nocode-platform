package com.mofanyi.ai.model;

import java.util.Set;

public record ModelDescriptor(
        String id,
        String provider,
        String modelName,
        int contextWindow,
        int maxOutputTokens,
        Set<ModelCapability> capabilities,
        String reasoningStyle,
        boolean enabled) {

    public ModelDescriptor {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public boolean supports(ModelCapability capability) {
        return capabilities.contains(capability);
    }
}
