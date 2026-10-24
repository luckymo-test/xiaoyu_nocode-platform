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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * React/Vite/TypeScript 从隔离构建到 Playwright 三档验证的真实探针。
 * 运行前需启动 browser-worker。
 */
@SpringBootTest(properties = {
        "codegen.runtime.react-vite-ts.enabled=true",
        "codegen.node.use-explicit-cli=false",
        "codegen.node.corepack-command=corepack",
        "codegen.sandbox.command-timeout-seconds=180",
        "codegen.browser.engine=playwright",
        "codegen.browser.playwright.url=http://127.0.0.1:8090",
        "codegen.browser.playwright.transport=http"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ReactViteBuiltDistPlaywrightProbeTest {

    private static final long APP_ID = 444710812068954114L;

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
    @DisplayName("React 构建产物完成 Playwright 三档验证")
    void buildsAndVerifiesReactDist() throws Exception {
        Path project = Path.of(files.getLiveProjectDir(CodeGenTypeEnum.VUE, APP_ID));
        deleteRecursively(project);
        copyFixture(project);
        StackProfile react = stackProfiles.require("react-vite-ts");
        stackProfileContext.activate(APP_ID, react);
        try {
            Path dist = buildService.build(APP_ID, null).toPath();
            assertTrue(Files.isRegularFile(dist.resolve("index.html")));

            RenderVerifier.RenderReport report =
                    verifier.verify(CodeGenTypeEnum.VUE, APP_ID, "react-probe", 102L);

            assertTrue(report.isUsable(), report.errors().toString());
            assertEquals(3, report.viewports().size());
            assertTrue(report.viewports().stream()
                    .allMatch(viewport -> viewport.textLength() >= 20));
        } finally {
            stackProfileContext.deactivate(APP_ID, react);
            deleteRecursively(project);
        }
    }

    private void copyFixture(Path target) throws Exception {
        Path source = new ClassPathResource("react-vite-ts-app").getFile().toPath();
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
