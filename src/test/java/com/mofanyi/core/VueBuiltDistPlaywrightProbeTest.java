package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.config.SandboxProperties;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Vue dist 三档验证探针，需要浏览器 Worker 运行：
 * {@code mvn -o test -Dtest=VueBuiltDistPlaywrightProbeTest -DcodegenProbe=true}
 */
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class VueBuiltDistPlaywrightProbeTest {

    private static final long APP_ID = 444710812068954112L;

    @Test
    @DisplayName("Vue 构建产物完成 Playwright 三档验证")
    void verifiesBuiltVueDist() {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(Path.of("tmp").toAbsolutePath().normalize().toString());
        Path dist = Path.of(files.getProjectDir(CodeGenTypeEnum.VUE, APP_ID), "dist");
        assertTrue(Files.isRegularFile(dist.resolve("index.html")));
        PlaywrightBrowserVerifier verifier = new PlaywrightBrowserVerifier(
                new ObjectMapper(), files,
                new HttpBrowserVerificationTransport(
                        "http://127.0.0.1:8090", 3_000, 120_000),
                new SandboxProperties());

        RenderVerifier.RenderReport report =
                verifier.verify(CodeGenTypeEnum.VUE, APP_ID, "vue-probe", 101L);

        assertTrue(report.isUsable(), report.errors().toString());
        assertEquals(3, report.viewports().size());
    }
}
