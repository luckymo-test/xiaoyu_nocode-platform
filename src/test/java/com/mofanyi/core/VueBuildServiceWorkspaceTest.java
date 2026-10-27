package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.workspace.ProjectWorkspaceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VueBuildServiceWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Job 构建读取隔离草稿，而不是尚未发布的 live 目录")
    void buildsCurrentJobWorkspace() {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        ProjectWorkspaceContext context = new ProjectWorkspaceContext();
        ReflectionTestUtils.setField(files, "workspaceContext", context);
        Path draft = tempDir.resolve("runs").resolve("99").resolve("workspace");
        context.activate(7L, draft);
        VueBuildService service = new VueBuildService(
                files, null, null, null, null, null, null, null);

        assertEquals(draft.toAbsolutePath().normalize(),
                service.buildProjectDir(7L));

        context.deactivate(7L, draft);
        assertEquals(Path.of(files.getLiveProjectDir(CodeGenTypeEnum.VUE, 7L))
                        .toAbsolutePath().normalize(),
                service.buildProjectDir(7L));
    }
}
