package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.workspace.ProjectWorkspaceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    @DisplayName("自动解析 Windows 和 Unix npm 全局前缀中的 CLI")
    void resolvesNpmCliFromGlobalPrefix() throws Exception {
        Path windows = tempDir.resolve("windows-prefix");
        Path windowsCli = windows.resolve("node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(windowsCli.getParent());
        Files.writeString(windowsCli, "");

        Path unix = tempDir.resolve("unix-prefix");
        Path unixCli = unix.resolve("lib/node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(unixCli.getParent());
        Files.writeString(unixCli, "");

        assertEquals(windowsCli, VueBuildService.resolveNpmCli(windows));
        assertEquals(unixCli, VueBuildService.resolveNpmCli(unix));
        assertNull(VueBuildService.resolveNpmCli(tempDir.resolve("missing")));
    }

    @Test
    @DisplayName("显式 CLI 使用 node + npm-cli.js，不经过 npm.cmd 或 shell")
    void buildsExplicitNpmCommand() {
        VueBuildService service = new VueBuildService(
                null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "useExplicitNodeCli", true);
        ReflectionTestUtils.setField(service, "nodeCommand", "node");
        ReflectionTestUtils.setField(service, "npmCliPath", "npm-cli.js");

        List<String> command = ReflectionTestUtils.invokeMethod(
                service, "buildProcessCommand", List.of("ci", "--ignore-scripts"));

        assertEquals(List.of("node", "npm-cli.js", "ci", "--ignore-scripts"), command);
    }
}
