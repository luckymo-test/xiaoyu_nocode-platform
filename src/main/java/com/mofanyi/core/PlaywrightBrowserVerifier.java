package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.config.SandboxProperties;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Playwright Node Execution Worker 的 HTTP Adapter。
 *
 * <p>Java 只提交一个有界工作区路径并接收结构化报告；浏览器进程完全位于 Node Worker。
 */
@Slf4j
@Service
@Conditional(GenerationExecutionCondition.class)
@ConditionalOnProperty(name = "codegen.browser.engine", havingValue = "playwright")
public class PlaywrightBrowserVerifier implements BrowserVerifierPort {

    private final ObjectMapper objectMapper;
    private final CodeFileWriteService codeFileWriteService;
    private final BrowserVerificationTransport transport;
    private final SandboxProperties sandbox;

    public PlaywrightBrowserVerifier(
            ObjectMapper objectMapper,
            CodeFileWriteService codeFileWriteService,
            BrowserVerificationTransport transport,
            SandboxProperties sandbox) {
        this.objectMapper = objectMapper;
        this.codeFileWriteService = codeFileWriteService;
        this.transport = transport;
        this.sandbox = sandbox;
    }

    @Override
    public RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId) {
        return verify(type, appId, "latest", null);
    }

    @Override
    public RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId, String stage) {
        return verify(type, appId, stage, null);
    }

    @Override
    public RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId,
                                              String stage, Long jobId) {
        if (type == null || appId == null || appId <= 0) {
            return new RenderVerifier.RenderReport(List.of(), List.of("参数非法"));
        }
        Path projectDir = Path.of(codeFileWriteService.getProjectDir(type, appId))
                .toAbsolutePath().normalize();
        File index = projectDir.resolve("index.html").toFile();
        if (!FileUtil.exist(index)) {
            return new RenderVerifier.RenderReport(
                    List.of(), List.of("未找到 index.html，跳过渲染验证"));
        }
        Path outputDir = projectDir.resolve(".agent");
        String safeStage = StrUtil.blankToDefault(stage, "latest")
                .replaceAll("[^a-zA-Z0-9_-]", "_");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("verificationId", (jobId == null ? appId : jobId) + "-" + safeStage);
        request.put("jobId", jobId == null ? null : String.valueOf(jobId));
        request.put("revisionId", null);
        request.put("sourceDir", projectDir.toString());
        request.put("outputDir", outputDir.toString());
        request.put("stage", safeStage);
        request.put("viewports", List.of(
                Map.of("name", "desktop", "label", "桌面", "width", 1440, "height", 900),
                Map.of("name", "tablet", "label", "平板", "width", 768, "height", 900),
                Map.of("name", "mobile", "label", "手机", "width", 375, "height", 800)));
        request.put("checks", List.of(
                "dom", "console", "network", "screenshot", "trace"));
        request.put("egressPolicy", sandbox.isEnabled() ? "generated-app" : "unrestricted");
        request.put("allowedHosts", sandbox.getEgress().getAllowedHosts());
        request.put("maxSourceFiles", sandbox.getMaxSourceFiles());
        request.put("maxSourceBytes", sandbox.getMaxSourceBytes());
        request.put("maxOutputFiles", sandbox.getMaxOutputFiles());
        request.put("maxOutputBytes", sandbox.getMaxOutputBytes());
        request.put("timeoutMs", sandbox.getCommandTimeoutSeconds() * 1000);

        try {
            String body = transport.execute(request);
            RenderVerifier.RenderReport report = parseReport(body);
            log.info("[verify] Playwright Worker 完成，appId={} stage={} {}",
                    appId, safeStage, report.summary());
            return report;
        } catch (RuntimeException e) {
            log.warn("[verify] Playwright Worker 不可用，跳过渲染验证，appId={}", appId, e);
            return new RenderVerifier.RenderReport(
                    List.of(), List.of("Playwright Worker 不可用：" + safeMessage(e)));
        }
    }

    @Override
    public void persistReport(CodeGenTypeEnum type, Long appId,
                              RenderVerifier.RenderReport report, String stage) {
        try {
            BrowserReportPersistence.persist(
                    codeFileWriteService, type, appId, report, stage);
        } catch (RuntimeException e) {
            log.warn("[verify] Playwright 报告落盘失败，appId={}", appId, e);
        }
    }

    RenderVerifier.RenderReport parseReport(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<RenderVerifier.ViewportResult> viewports = new ArrayList<>();
            for (JsonNode item : root.path("viewports")) {
                List<String> broken = strings(item.path("brokenImages"));
                List<String> consoleErrors = strings(item.path("consoleErrors"));
                viewports.add(new RenderVerifier.ViewportResult(
                        item.path("viewport").asText("unknown"),
                        item.path("label").asText(item.path("viewport").asText("unknown")),
                        item.path("width").asInt(0),
                        broken,
                        item.path("overflow").asBoolean(false),
                        item.path("scrollWidth").asInt(0),
                        item.path("clientWidth").asInt(0),
                        item.path("textLength").asInt(0),
                        consoleErrors,
                        nullableText(item.path("screenshotPath")),
                        nullableText(item.path("screenshotSha256"))));
            }
            return new RenderVerifier.RenderReport(viewports, strings(root.path("errors")));
        } catch (Exception e) {
            throw new IllegalStateException("Playwright Worker 报告非法：" + safeMessage(e), e);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(node -> values.add(node.asText("")));
        }
        return List.copyOf(values);
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static String safeMessage(Throwable error) {
        return error == null ? "unknown" : StrUtil.maxLength(
                StrUtil.blankToDefault(error.getMessage(), error.getClass().getSimpleName()), 400);
    }
}
