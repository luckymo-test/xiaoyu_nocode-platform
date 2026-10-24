package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Playwright Worker 探针，需要先启动 browser-worker：
 * {@code mvn -o test -Dtest=PlaywrightBrowserVerifierProbeTest -DcodegenProbe=true}
 */
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class PlaywrightBrowserVerifierProbeTest {

    @Test
    @DisplayName("Java Port 能通过 Playwright Worker 检出问题并接收截图/trace")
    void verifiesThroughPlaywrightWorker() throws Exception {
        long appId = 990001000000000091L;
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(Path.of("tmp").toAbsolutePath().normalize().toString());
        files.saveProjectFile(CodeGenTypeEnum.HTML, appId, "index.html", """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <style>body{margin:0}.wide{width:2000px;height:40px}</style></head>
                <body><h1>Playwright Java Port 探针</h1>
                <p>应检出图裂与横向溢出。</p>
                <img src="file:///definitely/not/exist/broken.png" alt="broken">
                <div class="wide"></div></body></html>
                """);
        PlaywrightBrowserVerifier verifier = new PlaywrightBrowserVerifier(
                new ObjectMapper(), files, "http://127.0.0.1:8090", 3_000, 120_000);

        RenderVerifier.RenderReport report =
                verifier.verify(CodeGenTypeEnum.HTML, appId, "probe", 99L);

        assertTrue(report.isUsable(), report.errors().toString());
        assertTrue(report.hasProblems());
        assertTrue(report.viewports().stream().allMatch(v -> v.screenshotPath() != null));
        Path agentDir = Path.of(files.getProjectDir(CodeGenTypeEnum.HTML, appId), ".agent");
        assertTrue(Files.isRegularFile(agentDir.resolve("trace-probe.zip")));
        assertFalse(Files.readString(agentDir.resolve("network-probe.json")).isBlank());
    }
}
