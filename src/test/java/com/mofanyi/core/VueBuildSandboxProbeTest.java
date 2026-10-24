package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 npm 构建沙箱探针。使用工作区中已有的无敏感 Vue 工程：
 * {@code mvn -o test -Dtest=VueBuildSandboxProbeTest -DcodegenProbe=true}
 */
@SpringBootTest(properties = {
        "codegen.sandbox.command-timeout-seconds=180",
        "codegen.sandbox.build.ignore-scripts=true"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class VueBuildSandboxProbeTest {

    private static final long APP_ID = 444710812068954112L;

    @Autowired
    private VueBuildService buildService;

    @Autowired
    private CodeFileWriteService files;

    @Test
    @DisplayName("Vue 工程在隔离目录中 npm ci/build 并写构建报告")
    void buildsInSandbox() {
        Path project = Path.of(files.getLiveProjectDir(CodeGenTypeEnum.VUE, APP_ID));
        Path dist = buildService.build(APP_ID, null).toPath();
        Path report = project.resolve(".agent").resolve("build-report.json");

        assertTrue(Files.isRegularFile(dist.resolve("index.html")));
        assertTrue(Files.isRegularFile(report));
        Path buildsRoot = Path.of(files.getTmpRoot()).resolve("sandbox").resolve("builds");
        if (Files.isDirectory(buildsRoot)) {
            try (var entries = Files.list(buildsRoot)) {
                assertFalse(entries.findAny().isPresent(),
                        "构建结束后必须清理临时沙箱");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
