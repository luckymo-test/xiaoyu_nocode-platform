package com.mofanyi.template;

import com.fasterxml.jackson.databind.JsonNode;

public record ProjectTemplateDefinition(
        String id,
        String displayName,
        String description,
        String initialPrompt,
        String visualTemplate,
        JsonNode schema) {
}
