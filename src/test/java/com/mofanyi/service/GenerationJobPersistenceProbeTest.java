package com.mofanyi.service;

import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.GenerationJobEvent;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.entity.AppArtifact;
import com.mofanyi.entity.GenerationDeadLetter;
import com.mofanyi.entity.LibraryAsset;
import com.mofanyi.entity.LibraryAssetVariant;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.mapper.AppArtifactMapper;
import com.mofanyi.mapper.GenerationDeadLetterMapper;
import com.mofanyi.mapper.LibraryAssetMapper;
import com.mofanyi.mapper.LibraryAssetVariantMapper;
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

    @Autowired
    private AppRevisionMapper revisionMapper;

    @Autowired
    private AppArtifactMapper artifactMapper;

    @Autowired
    private GenerationDeadLetterMapper deadLetterMapper;

    @Autowired
    private LibraryAssetMapper assetMapper;

    @Autowired
    private LibraryAssetVariantMapper assetVariantMapper;

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
                .stackProfileId("html-static")
                .templateBundleId("default-neutral")
                .templateVersion("1")
                .templateHash("probe-template-hash")
                .skillsJson("[]")
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

        AppRevision revision = AppRevision.builder()
                .workspaceId(990001000000000001L)
                .appId(990001000000000002L)
                .jobId(job.getId())
                .revisionNo(1L)
                .status("CREATED")
                .codeGenType("html")
                .templateBundleId("default-neutral")
                .templateVersion("1")
                .templateHash("probe-template-hash")
                .skillsJson("[]")
                .sourceUri("file:///probe/source")
                .validationStatus("ok")
                .createdBy(1L)
                .createTime(now)
                .updateTime(now)
                .build();
        assertEquals(1, revisionMapper.insertSelective(revision));
        AppArtifact artifact = AppArtifact.builder()
                .revisionId(revision.getId())
                .jobId(job.getId())
                .appId(job.getAppId())
                .workspaceId(job.getWorkspaceId())
                .artifactType("SOURCE_TREE")
                .scope("PROJECT")
                .ownerId(1L)
                .visibility("private")
                .uri("file:///probe/source")
                .sha256("probe")
                .sizeBytes(1L)
                .mimeType("application/vnd.xiaoyu.source-tree")
                .metadata("{}")
                .createTime(now)
                .build();
        assertEquals(1, artifactMapper.insertSelective(artifact));

        GenerationDeadLetter deadLetter = GenerationDeadLetter.builder()
                .jobId(job.getId())
                .workspaceId(job.getWorkspaceId())
                .appId(job.getAppId())
                .requestedBy(1L)
                .attempt(1)
                .errorType("TIMEOUT")
                .errorMessage("probe")
                .payload("{}")
                .status("PENDING")
                .createTime(now)
                .updateTime(now)
                .build();
        assertEquals(1, deadLetterMapper.insertSelective(deadLetter));

        LibraryAsset asset = LibraryAsset.builder()
                .workspaceId(job.getWorkspaceId())
                .ownerId(1L)
                .assetType("IMAGE")
                .sourceType("UPLOAD")
                .displayName("probe.png")
                .originalUri("file:///probe.png")
                .sha256("probe-asset-hash")
                .mimeType("image/png")
                .extension("png")
                .sizeBytes(10L)
                .width(2)
                .height(2)
                .visibility("private")
                .moderationStatus("APPROVED")
                .metadata("{}")
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
        assertEquals(1, assetMapper.insertSelective(asset));
        LibraryAssetVariant variant = LibraryAssetVariant.builder()
                .assetId(asset.getId())
                .variantType("ORIGINAL")
                .uri(asset.getOriginalUri())
                .format("png")
                .width(2)
                .height(2)
                .sizeBytes(10L)
                .createTime(now)
                .build();
        assertEquals(1, assetVariantMapper.insertSelective(variant));

        assertTrue(workspaceMapper.selectCountByQuery(
                com.mybatisflex.core.query.QueryWrapper.create()) > 0);
    }
}
