package com.mofanyi.service;

import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.GenerationJobEvent;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.mapper.WorkspaceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实数据库探针：
 * {@code mvn -o test -Dtest=GenerationJobPersistenceProbeTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class GenerationJobPersistenceProbeTest {

    @Autowired
    private GenerationJobMapper jobMapper;

    @Autowired
    private GenerationJobEventService eventService;

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Test
    @Transactional
    @DisplayName("新 Job/Event 表可写，迁移已为已有用户回填工作区")
    void persistsJobAndEventAgainstMySql57() {
        LocalDateTime now = LocalDateTime.now();
        GenerationJob job = GenerationJob.builder()
                .workspaceId(990001000000000001L)
                .appId(990001000000000002L)
                .requestedBy(1L)
                .requestType("generate")
                .idempotencyKey("probe-" + System.nanoTime())
                .requestHash("probe-hash")
                .requestPayload("{}")
                .userMessageId(null)
                .status("QUEUED")
                .currentStage("QUEUED")
                .codeGenType("html")
                .visibility("private")
                .priority(0)
                .attempt(0)
                .maxAttempts(1)
                .fencingToken(0L)
                .nextEventSeq(0L)
                .createTime(now)
                .updateTime(now)
                .build();

        assertEquals(1, jobMapper.insertSelective(job));
        assertNotNull(job.getId());

        GenerationJobEvent event = eventService.append(job.getId(), "stage", "GENERATE",
                java.util.Map.of("stage", "GENERATE", "status", "RUNNING"));
        assertEquals(1L, event.getSeq());
        assertEquals("RUNNING", eventService.readPayload(event).path("status").asText());

        assertTrue(workspaceMapper.selectCountByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()) > 0);
    }
}
