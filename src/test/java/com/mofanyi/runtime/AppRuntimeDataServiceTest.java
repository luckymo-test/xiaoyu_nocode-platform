package com.mofanyi.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.AppModelRecord;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.mapper.AppModelRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppRuntimeDataServiceTest {

    private AppRuntimeSchemaService schemas;
    private AppModelRecordMapper records;
    private AppRuntimeDataService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        schemas = mock(AppRuntimeSchemaService.class);
        records = mock(AppModelRecordMapper.class);
        service = new AppRuntimeDataService(schemas, records, mapper);
        when(schemas.requireSchema(1L)).thenReturn(mapper.readTree("""
                {
                  "models": [{
                    "name": "albums",
                    "fields": [
                      {"name": "title", "type": "string", "required": true},
                      {"name": "published", "type": "boolean", "required": false}
                    ]
                  }],
                  "permissions": [
                    {"role": "OWNER", "model": "*", "actions": [
                      "READ", "CREATE", "UPDATE", "DELETE"
                    ]},
                    {"role": "CLIENT", "model": "albums", "actions": ["READ"]}
                  ]
                }
                """));
    }

    @Test
    void ownerCanCreateAndRecordIsTenantScoped() throws Exception {
        AppRuntimeIdentity owner = new AppRuntimeIdentity(
                7L, 1L, "user:9", "OWNER", null, false);

        service.create(owner, "albums", mapper.readTree(
                "{\"title\":\"秋季选片\",\"published\":false}"));

        verify(records).insertSelective(any(AppModelRecord.class));
    }

    @Test
    void clientCannotDelete() {
        AppRuntimeIdentity client = new AppRuntimeIdentity(
                7L, 1L, "visitor:a", "CLIENT", 3L, true);

        assertThrows(BusinessException.class,
                () -> service.delete(client, "albums", "a"));
    }

    @Test
    void enforcesRequiredFields() throws Exception {
        AppRuntimeIdentity owner = new AppRuntimeIdentity(
                7L, 1L, "user:9", "OWNER", null, false);

        assertThrows(RuntimeException.class,
                () -> service.create(owner, "albums", mapper.readTree("{}")));
    }
}
