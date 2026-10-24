package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.mofanyi.model.enums.CodeGenTypeEnum;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * 不同浏览器 Adapter 共用的报告落盘格式。
 */
public final class BrowserReportPersistence {

    private BrowserReportPersistence() {
    }

    public static void persist(CodeFileWriteService files, CodeGenTypeEnum type,
                               Long appId, RenderVerifier.RenderReport report,
                               String stage) {
        if (report == null) {
            return;
        }
        JSONObject root = new JSONObject();
        root.set("generatedAt", java.time.LocalDateTime.now().toString());
        root.set("hasProblems", report.hasProblems());
        JSONArray viewports = new JSONArray();
        if (report.viewports() != null) {
            for (RenderVerifier.ViewportResult viewport : report.viewports()) {
                JSONObject item = new JSONObject();
                item.set("viewport", viewport.name());
                item.set("width", viewport.width());
                item.set("brokenImages", viewport.brokenImages());
                item.set("overflow", viewport.overflow());
                item.set("scrollWidth", viewport.scrollWidth());
                item.set("clientWidth", viewport.clientWidth());
                item.set("textLength", viewport.textLength());
                item.set("consoleErrors", viewport.consoleErrors());
                item.set("screenshotPath", viewport.screenshotPath());
                item.set("screenshotSha256", viewport.screenshotSha256());
                viewports.add(item);
            }
        }
        root.set("viewports", viewports);
        root.set("errors", report.errors());
        String safeStage = stage == null ? "latest" : stage.replaceAll("[^a-zA-Z0-9_-]", "_");
        String projectDir = files.getProjectDir(type, appId);
        File out = Paths.get(projectDir, ".agent", "verify-" + safeStage + ".json").toFile();
        FileUtil.mkParentDirs(out);
        FileUtil.writeString(root.toStringPretty(), out, StandardCharsets.UTF_8);
    }
}
