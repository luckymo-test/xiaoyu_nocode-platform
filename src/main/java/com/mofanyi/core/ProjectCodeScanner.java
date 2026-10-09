package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工程代码扫描：文件遍历 / 相对路径 / 图片 URL 落库 / COS 图计数
 * （plan-refactor 7.1 拆分第 S1 步，从 VueProjectAgent 抽出）。
 *
 * <p>这三个能力原本散在 {@code VueProjectAgent} 里，且被 {@code autoFillImages}、{@code countCosUrls}、
 * {@code buildFileIndex} 共用——这也是"为什么必须分步拆"的原因（见文档 7.1）。
 */
@Slf4j
@Component
public class ProjectCodeScanner {

    /**
     * 只认这些扩展名，避免把 .agent/spec.md 之类的辅助文件当代码文件
     */
    private static final List<String> CODE_EXTENSIONS = List.of(".html", ".css", ".js", ".vue", ".json");

    /**
     * 从代码里提取图片 URL
     */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\"'\\s)\\]}]+?\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\"'\\s)]*)?");

    /**
     * 平台 COS 图片的 key 前缀（用于计数）
     */
    private static final Pattern COS_MARK = Pattern.compile("app-image/");

    private final CodeFileWriteService codeFileWriteService;
    private final SiteImageService siteImageService;

    public ProjectCodeScanner(CodeFileWriteService codeFileWriteService, SiteImageService siteImageService) {
        this.codeFileWriteService = codeFileWriteService;
        this.siteImageService = siteImageService;
    }

    /**
     * 递归收集工程下的代码文件（排除 node_modules / dist），按扩展名白名单过滤。
     *
     * @param dir 工程根目录
     * @return 代码文件列表（未排序，调用方可自行排序）
     */
    public List<File> collectCodeFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        collect(dir, out);
        return out;
    }

    private void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if ("node_modules".equals(name) || "dist".equals(name)) {
                    continue;
                }
                collect(child, out);
            } else {
                String name = child.getName().toLowerCase();
                if (CODE_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                    out.add(child);
                }
            }
        }
    }

    /**
     * 计算文件相对工程根目录的路径（统一用 / 分隔）
     */
    public String relativePath(File root, File file) {
        String rel = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                .toString().replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    /**
     * 扫描项目代码文件中的图片 URL（&lt;img src&gt;、background-image、数据数组等），写入 app_image。
     * 与 searchImage 落库互补，保证生成的页面图片可靠入库。
     */
    public void scanAndSaveImages(CodeGenTypeEnum type, Long appId) {
        try {
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            File root = new File(projectDir);
            if (!root.isDirectory()) {
                log.info("[scan] 项目目录不存在，跳过，dir={}", projectDir);
                return;
            }
            Set<String> urls = new LinkedHashSet<>();
            for (File file : collectCodeFiles(root)) {
                try {
                    Matcher matcher = IMAGE_URL.matcher(FileUtil.readUtf8String(file));
                    while (matcher.find()) {
                        urls.add(matcher.group().replace("\\/", "/"));
                    }
                } catch (Exception ignore) {
                    // 单文件读取失败不影响整体
                }
            }
            log.info("[scan] 扫描到 {} 个图片 URL，appId={}", urls.size(), appId);
            for (String url : urls) {
                if (url.startsWith("http")) {
                    siteImageService.saveAppImage(appId, url);
                }
            }
        } catch (RuntimeException e) {
            log.warn("扫描落库图片失败，appId={}", appId, e);
        }
    }

    /**
     * 统计工程目录里已生成的 COS 图片 URL 数量（按 key 前缀计数）
     */
    public int countCosUrls(String projectDir) {
        int count = 0;
        for (File file : collectCodeFiles(new File(projectDir))) {
            try {
                Matcher matcher = COS_MARK.matcher(FileUtil.readUtf8String(file));
                while (matcher.find()) {
                    count++;
                }
            } catch (Exception ignore) {
                // 单文件读取失败不影响计数
            }
        }
        return count;
    }
}
