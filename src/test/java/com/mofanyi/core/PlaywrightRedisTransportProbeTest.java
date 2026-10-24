package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis Streams 真实传输探针，需要 Redis 和 browser-worker 同时运行：
 * {@code mvn -o test -Dtest=PlaywrightRedisTransportProbeTest -DcodegenProbe=true}
 */
@SpringBootTest(properties = {
        "codegen.browser.engine=playwright",
        "codegen.browser.playwright.transport=redis",
        "codegen.verify.enabled=true"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class PlaywrightRedisTransportProbeTest {

    @Autowired
    private BrowserVerifierPort verifier;

    @Autowired
    private CodeFileWriteService files;

    @Test
    @DisplayName("验证任务经 Redis Streams 下发并由 Worker 回写结果")
    void verifiesThroughRedisStream() {
        long appId = 990001000000000092L;
        files.saveProjectFile(CodeGenTypeEnum.HTML, appId, "index.html", """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <style>body{margin:0}.wide{width:2000px;height:40px}</style></head>
                <body><h1>Redis Stream 验证探针</h1>
                <p>此页面应检出横向溢出。</p><div class="wide"></div></body></html>
                """);

        RenderVerifier.RenderReport report =
                verifier.verify(CodeGenTypeEnum.HTML, appId, "redis-probe", 100L);

        assertTrue(report.isUsable(), report.errors().toString());
        assertTrue(report.hasProblems());
        assertTrue(report.viewports().stream().allMatch(
                viewport -> viewport.width() > 0));
    }
}
