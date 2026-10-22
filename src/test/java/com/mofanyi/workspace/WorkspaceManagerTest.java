package com.mofanyi.workspace;

import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.GenerationJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Job 从 live 复制草稿，Agent 写入只进入隔离目录，关闭后清理 run")
    void isolatesGenerationWrites() throws Exception {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        ProjectWorkspaceContext context = new ProjectWorkspaceContext();
        ReflectionTestUtils.setField(files, "workspaceContext", context);
        Files.createDirectories(Path.of(files.getLiveProjectDir(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L)));
        Files.writeString(Path.of(files.getLiveProjectDir(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L), "index.html"),
                "live", StandardCharsets.UTF_8);

        WorkspaceManager manager = new WorkspaceManager(
                files, context, mock(com.mofanyi.mapper.AppRevisionMapper.class));
        GenerationJob job = GenerationJob.builder()
                .id(99L).appId(7L).codeGenType("html").build();
        WorkspaceManager.WorkspaceHandle handle = manager.begin(job);

        Path projectDir = Path.of(files.getProjectDir(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L));
        assertEquals(handle.root().toAbsolutePath().normalize(), projectDir);
        assertEquals("live", Files.readString(projectDir.resolve("index.html")));
        files.saveProjectFile(com.mofanyi.model.enums.CodeGenTypeEnum.HTML,
                7L, "index.html", "draft");
        assertEquals("live", Files.readString(Path.of(files.getLiveProjectDir(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L), "index.html")));
        assertEquals("draft", Files.readString(projectDir.resolve("index.html")));

        manager.close(handle);
        assertFalse(Files.exists(handle.root()));
        assertEquals(tempDir.resolve("html_7").toAbsolutePath().normalize(),
                Path.of(files.getProjectDir(com.mofanyi.model.enums.CodeGenTypeEnum.HTML, 7L)));
        assertTrue(context.resolve(7L) == null);
    }

    @Test
    @DisplayName("启动时 live 缺失可从旧 backup 恢复，并清理遗留 stage")
    void recoversInterruptedPublish() throws Exception {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        Path backup = tempDir.resolve("html_8.backup-55");
        Files.createDirectories(backup);
        Files.writeString(backup.resolve("index.html"), "old", StandardCharsets.UTF_8);
        Path stale = tempDir.resolve("html_8.publish-56");
        Files.createDirectories(stale);
        FileTime old = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(backup, old);
        Files.setLastModifiedTime(stale, old);

        new WorkspaceRecoveryRunner(files).run(mock(
                org.springframework.boot.ApplicationArguments.class));

        assertEquals("old", Files.readString(tempDir.resolve("html_8/index.html")));
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(stale));
    }
}
