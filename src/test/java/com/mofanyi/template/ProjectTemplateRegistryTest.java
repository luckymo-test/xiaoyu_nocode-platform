package com.mofanyi.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTemplateRegistryTest {

    @Test
    void loadsThreeRunnableSchemasWithDemoData() {
        ProjectTemplateRegistry registry = new ProjectTemplateRegistry(new ObjectMapper());

        assertEquals(4, registry.list().size());
        for (ProjectTemplateDefinition template : registry.list()) {
            assertEquals(template.id(), template.schema().path("projectTemplateId").asText());
            assertTrue(template.schema().path("models").isArray());
            assertFalse(template.schema().path("models").isEmpty());
            assertTrue(template.schema().path("permissions").isArray());
            assertFalse(template.schema().path("permissions").isEmpty());
            assertTrue(template.schema().path("seedData").isArray());
            assertFalse(template.schema().path("seedData").isEmpty());
        }
    }
}
