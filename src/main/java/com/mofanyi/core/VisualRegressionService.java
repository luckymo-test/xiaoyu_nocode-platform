package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 确定性视觉回归：比较 before/after 同视口截图的像素变化比例。
 * 它只回答“变化多少”，不代表审美质量提升。
 */
@Service
public class VisualRegressionService {

    private static final double CHANGED_RATIO_THRESHOLD = 0.02;
    private static final int PIXEL_DELTA_THRESHOLD = 24;

    public record ViewportDiff(String viewport, int width, int height,
                               long changedPixels, long totalPixels,
                               double changedRatio, boolean passed) {
    }

    public JSONObject compareAndPersist(CodeFileWriteService files,
                                        CodeGenTypeEnum type, Long appId) {
        Path screenshotDir = Paths.get(files.getProjectDir(type, appId),
                ".agent", "screenshots");
        JSONArray viewports = new JSONArray();
        if (Files.isDirectory(screenshotDir)) {
            for (String viewport : new String[]{"desktop", "tablet", "mobile"}) {
                File before = screenshotDir.resolve("before-" + viewport + ".png").toFile();
                File after = screenshotDir.resolve("after-" + viewport + ".png").toFile();
                if (before.isFile() && after.isFile()) {
                    ViewportDiff diff = compare(before, after, viewport);
                    JSONObject row = new JSONObject();
                    row.set("viewport", diff.viewport());
                    row.set("width", diff.width());
                    row.set("height", diff.height());
                    row.set("changedPixels", diff.changedPixels());
                    row.set("totalPixels", diff.totalPixels());
                    row.set("changedRatio", diff.changedRatio());
                    row.set("passed", diff.passed());
                    viewports.add(row);
                }
            }
        }
        JSONObject report = new JSONObject();
        report.set("generatedAt", java.time.LocalDateTime.now().toString());
        report.set("semantics", "pixel-change-only; not an aesthetic score");
        report.set("viewports", viewports);
        File out = screenshotDir.resolve("visual-diff.json").toFile();
        FileUtil.mkParentDirs(out);
        FileUtil.writeString(report.toStringPretty(), out, StandardCharsets.UTF_8);
        return report;
    }

    ViewportDiff compare(File before, File after, String viewport) {
        try {
            BufferedImage left = ImageIO.read(before);
            BufferedImage right = ImageIO.read(after);
            if (left == null || right == null) {
                return new ViewportDiff(viewport, 0, 0, 0, 0, 0, false);
            }
            int width = Math.min(left.getWidth(), right.getWidth());
            int height = Math.min(left.getHeight(), right.getHeight());
            long changed = 0;
            long total = (long) width * height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (different(left.getRGB(x, y), right.getRGB(x, y))) {
                        changed++;
                    }
                }
            }
            double ratio = total == 0 ? 1 : (double) changed / total;
            return new ViewportDiff(viewport, width, height, changed, total,
                    ratio, ratio <= CHANGED_RATIO_THRESHOLD);
        } catch (Exception e) {
            return new ViewportDiff(viewport, 0, 0, 0, 0, 0, false);
        }
    }

    private boolean different(int left, int right) {
        int lr = (left >> 16) & 0xff;
        int lg = (left >> 8) & 0xff;
        int lb = left & 0xff;
        int rr = (right >> 16) & 0xff;
        int rg = (right >> 8) & 0xff;
        int rb = right & 0xff;
        return Math.abs(lr - rr) > PIXEL_DELTA_THRESHOLD
                || Math.abs(lg - rg) > PIXEL_DELTA_THRESHOLD
                || Math.abs(lb - rb) > PIXEL_DELTA_THRESHOLD;
    }
}
