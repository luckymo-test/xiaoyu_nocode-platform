package com.mofanyi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.GenerationJobEvent;
import com.mofanyi.mapper.GenerationJobEventMapper;
import com.mofanyi.mapper.GenerationJobMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationJobEventServiceTest {

    @Test
    @DisplayName("事件序号单调分配，文件进度以 JSON 对象保存")
    void append_allocatesSequenceAndKeepsStructuredPayload() {
        GenerationJobMapper jobMapper = mock(GenerationJobMapper.class);
        GenerationJobEventMapper eventMapper = mock(GenerationJobEventMapper.class);
        when(jobMapper.lockNextEventSeq(11L)).thenReturn(4L);
        when(jobMapper.updateNextEventSeq(11L, 5L, "GENERATE")).thenReturn(1);
        when(eventMapper.insertSelective(any())).thenReturn(1);
        GenerationJobEventService service = new GenerationJobEventService(
                jobMapper, eventMapper, new ObjectMapper());

        GenerationJobEvent event = service.append(11L, "progress", "GENERATE",
                "{\"path\":\"index.html\",\"content\":\"<html>\"}");

        assertEquals(5L, event.getSeq());
        JsonNode payload = service.readPayload(event);
        assertTrue(payload.isObject());
        assertEquals("index.html", payload.path("path").asText());
        verify(jobMapper).updateNextEventSeq(11L, 5L, "GENERATE");
    }
}
