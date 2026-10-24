package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.runtime.StackProfile;
import com.mofanyi.runtime.StackProfileContext;
import com.mofanyi.runtime.StackProfileRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Next.js App Router + Tailwind 4 静态导出探针。
 */
@SpringBootTest(properties = {
        "codegen.runtime.next-static-export.enabled=true",
        "codegen.node.use-explicit-cli=false",
        "codegen.node.corepack-command=corepack",
        "codegen.sandbox.command-timeout-seconds=300",
        "codegen.browser.engine=playwright",
        "codegen.browser.playwright.url=http://127.0.0.1:8090",
        "codegen.browser.playwright.transport=http"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class NextStaticExportBuildSandboxProbeTest {

    private static final long APP_ID = 444710812068954115L;

    @Autowired
    private VueBuildService buildService;

    @Autowired
    private CodeFileWriteService files;

    @Autowired
    private StackProfileContext stackProfileContext;

    @Autowired
    private StackProfileRegistry stackProfiles;

    @Autowired
    private BrowserVerifierPort verifier;

    @Test
    @DisplayName("Next 工程完成静态导出并生成 out")
    void exportsNextProjectInSandbox() throws Exception {
        Path project = Path.of(files.getLiveProjectDir(CodeGenTypeEnum.VUE, APP_ID));
        deleteRecursively(project);
        copyFixture(project);
        StackProfile next = stackProfiles.require("next-static-export");
        stackProfileContext.activate(APP_ID, next);
        try {
            Path out = buildService.build(APP_ID, null).toPath();
            Path report = project.resolve(".agent").resolve("build-report.json");

            assertTrue(Files.isRegularFile(out.resolve("index.html")));
            assertTrue(Files.isRegularFile(report));
            String reportText = Files.readString(report);
            assertTrue(reportText.contains("\"stackProfileId\" : \"next-static-export\""));
            RenderVerifier.RenderReport renderReport =
                    verifier.verify(CodeGenTypeEnum.VUE, APP_ID, "next-probe", 103L);
            assertTrue(renderReport.isUsable(), renderReport.errors().toString());
            assertEquals(3, renderReport.viewports().size());
            assertTrue(renderReport.viewports().stream()
                    .allMatch(viewport -> viewport.textLength() >= 20));
            Path buildsRoot = Path.of(files.getTmpRoot()).resolve("sandbox").resolve("builds");
            if (Files.isDirectory(buildsRoot)) {
                try (var entries = Files.list(buildsRoot)) {
                    assertFalse(entries.findAny().isPresent(),
                            "构建结束后必须清理临时沙箱");
                }
            }
        } finally {
            stackProfileContext.deactivate(APP_ID, next);
            deleteRecursively(project);
        }
    }

    private void copyFixture(Path target) throws Exception {
        Path source = new ClassPathResource("next-static-export-app").getFile().toPath();
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
