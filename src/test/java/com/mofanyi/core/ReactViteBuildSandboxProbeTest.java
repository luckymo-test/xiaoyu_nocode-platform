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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 React/Vite/TypeScript npm ci + tsc + build 探针。
 */
@SpringBootTest(properties = {
        "codegen.runtime.react-vite-ts.enabled=true",
        "codegen.node.use-explicit-cli=false",
        "codegen.node.corepack-command=corepack",
        "codegen.sandbox.command-timeout-seconds=180",
        "codegen.sandbox.build.ignore-scripts=true"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ReactViteBuildSandboxProbeTest {

    private static final long APP_ID = 444710812068954113L;

    @Autowired
    private VueBuildService buildService;

    @Autowired
    private CodeFileWriteService files;

    @Autowired
    private StackProfileContext stackProfileContext;

    @Autowired
    private StackProfileRegistry stackProfiles;

    @Test
    @DisplayName("React 工程完成受控 npm ci/build 并产出 dist")
    void buildsReactProjectInSandbox() throws Exception {
        Path project = Path.of(files.getLiveProjectDir(CodeGenTypeEnum.VUE, APP_ID));
        deleteRecursively(project);
        copyFixture(project);
        StackProfile react = stackProfiles.require("react-vite-ts");
        stackProfileContext.activate(APP_ID, react);
        try {
            Path dist = buildService.build(APP_ID, null).toPath();
            Path report = project.resolve(".agent").resolve("build-report.json");

            assertTrue(Files.isRegularFile(dist.resolve("index.html")));
            assertTrue(Files.isRegularFile(report));
            String reportText = Files.readString(report);
            assertTrue(reportText.contains("\"stackProfileId\" : \"react-vite-ts\""));
            Path buildsRoot = Path.of(files.getTmpRoot()).resolve("sandbox").resolve("builds");
            if (Files.isDirectory(buildsRoot)) {
                try (var entries = Files.list(buildsRoot)) {
                    assertFalse(entries.findAny().isPresent(),
                            "构建结束后必须清理临时沙箱");
                }
            }
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
