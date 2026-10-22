package com.mofanyi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.artifact.ArtifactStore;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.mapper.AppArtifactMapper;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.workspace.ProjectWorkspaceContext;
import com.mofanyi.workspace.WorkspaceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppRevisionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Job 草稿快照成 Revision、写 Artifact，并用 CAS 原子切换 live")
    void publishesImmutableRevision() throws Exception {
        AppRevisionMapper revisionMapper = mock(AppRevisionMapper.class);
        AppArtifactMapper artifactMapper = mock(AppArtifactMapper.class);
        AppMapper appMapper = mock(AppMapper.class);
        GenerationJobService jobService = mock(GenerationJobService.class);
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        ProjectWorkspaceContext context = new ProjectWorkspaceContext();
        WorkspaceManager workspaceManager = new WorkspaceManager(files, context, revisionMapper);
        AppRevisionService service = new AppRevisionService(
                revisionMapper, artifactMapper, appMapper, workspaceManager,
                new ArtifactStore(), files, new ObjectMapper(), jobService);

        App app = new App();
        app.setId(7L);
        app.setUserId(3L);
        when(appMapper.selectOneById(7L)).thenReturn(app);
        when(appMapper.compareAndSetCurrentRevision(7L, 44L, 55L)).thenReturn(1);
        when(revisionMapper.selectOneByQuery(any())).thenReturn(null);
        when(revisionMapper.nextRevisionNo(7L)).thenReturn(0L);
        when(revisionMapper.insertSelective(any())).thenAnswer(invocation -> {
            AppRevision revision = invocation.getArgument(0);
            revision.setId(55L);
            return 1;
        });
        when(revisionMapper.update(any(AppRevision.class))).thenReturn(1);
        when(artifactMapper.insertSelective(any())).thenReturn(1);

        Path workspace = tempDir.resolve("runs/99/workspace");
        Files.createDirectories(workspace.resolve(".agent"));
        Files.writeString(workspace.resolve("index.html"), "hello", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".agent/materials.json"), "{}", StandardCharsets.UTF_8);
        GenerationJob job = GenerationJob.builder()
                .id(99L)
                .appId(7L)
                .workspaceId(8L)
                .requestedBy(3L)
                .requestType("generate")
                .requestPayload("{\"message\":\"hello\"}")
                .status("RUNNING")
                .currentStage("GENERATE")
                .codeGenType("html")
                .visibility("private")
                .baseRevisionId(44L)
                .fencingToken(1L)
                .build();

        AppRevision revision = service.publishJob(
                job, new WorkspaceManager.WorkspaceHandle(7L, workspace), "ok");

        assertEquals(55L, revision.getId());
        assertNotNull(revision.getManifest());
        assertTrue(revision.getSourceUri().startsWith("file:"));
        assertTrue(Files.isDirectory(Path.of(URI.create(revision.getSourceUri()))));
        Path live = Path.of(files.getLiveProjectDir(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L));
        assertEquals("hello", Files.readString(live.resolve("index.html")));
        verify(appMapper).compareAndSetCurrentRevision(7L, 44L, 55L);
        verify(jobService).recordPublishedRevision(job, 55L);
    }
}
