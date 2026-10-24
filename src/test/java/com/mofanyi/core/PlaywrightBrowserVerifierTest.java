package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlaywrightBrowserVerifierTest {

    private final PlaywrightBrowserVerifier verifier = new PlaywrightBrowserVerifier(
            new ObjectMapper(),
            mock(CodeFileWriteService.class),
            "http://127.0.0.1:8090",
            1_000,
            5_000);

    @Test
    @DisplayName("Playwright Worker JSON 映射为统一渲染报告")
    void parsesWorkerReport() {
        RenderVerifier.RenderReport report = verifier.parseReport("""
                {
                  "viewports": [
                    {
                      "viewport": "desktop",
                      "label": "桌面",
                      "width": 1440,
                      "brokenImages": ["https://example.test/broken.png"],
                      "overflow": true,
                      "scrollWidth": 1600,
                      "clientWidth": 1440,
                      "textLength": 42,
                      "consoleErrors": ["CORS blocked"],
                      "screenshotPath": "D:/tmp/desktop.png",
                      "screenshotSha256": "abc"
                    }
                  ],
                  "errors": []
                }
                """);

        assertEquals(1, report.viewports().size());
        assertTrue(report.hasProblems());
        assertTrue(report.toPromptText().contains("横向溢出"));
        assertTrue(report.toPromptText().contains("控制台报错"));
        assertEquals("abc", report.viewports().getFirst().screenshotSha256());
    }
}
