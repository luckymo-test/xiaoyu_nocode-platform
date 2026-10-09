package com.mofanyi.ai.memory;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用"规格摘要 + 代码库索引"服务（plan-agent-engineering.md 第二章 M2）。
 *
 * <p>解决"聊天记录 ≠ 应用现状"的范式错配：续接任务时模型真正需要的是
 * <b>这个应用要做什么、已实现什么、有哪些文件</b>，而不是三天前的聊天原文。
 *
 * <p>设计取舍（对应文档 2.6 的 5 个问题）：
 * <ul>
 *   <li><b>生成时机</b>：不额外调用模型——每次生成/修改/修复/手动编辑后，<b>确定性追加</b>一条变更记录（增量）。</li>
 *   <li><b>一致性</b>：由<b>写路径触发</b>更新（所有会改代码的入口都调用 {@link #recordChange}），因此摘要不会滞后于代码。</li>
 *   <li><b>存放位置</b>：工程目录下 {@code .agent/spec.md}——随项目生命周期创建/删除，不额外建表。</li>
 *   <li><b>索引粒度</b>：文件路径清单（不含每文件职责描述，避免额外模型调用）。</li>
 *   <li><b>成本</b>：零额外模型调用。</li>
 * </ul>
 *
 * <p>注意：{@code .agent} 目录不属于交付物，已从代码读取 / 下载的遍历中排除。
 */
@Slf4j
@Service
public class AppSpecService {

    /**
     * 规格文件所在目录（工程目录下）
     */
    private static final String AGENT_DIR = ".agent";

    /**
     * 规格文件名
     */
    private static final String SPEC_FILE = "spec.md";

    /**
     * 变更记录保留条数上限（超出丢弃最旧）
     */
    private static final int MAX_CHANGE_ENTRIES = 20;

    /**
     * 变更记录分节标记
     */
    private static final String HEADER_MARK = "## 变更记录";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CodeFileWriteService codeFileWriteService;
    private final AppMapper appMapper;

    public AppSpecService(CodeFileWriteService codeFileWriteService, AppMapper appMapper) {
        this.codeFileWriteService = codeFileWriteService;
        this.appMapper = appMapper;
    }

    /**
     * 构造注入模型的"应用现状"文本块：规格摘要 + 现有代码文件索引。
     * 没有可注入内容时返回空串（调用方据此跳过注入）。
     *
     * @param type  代码生成类型
     * @param appId 应用 id
     * @return 可直接作为一条 SystemMessage 内容的事实块；无内容时返回 ""
     */
    public String buildSpecSection(CodeGenTypeEnum type, Long appId) {
        if (type == null || appId == null || appId <= 0) {
            return "";
        }
        try {
            String specText = readRawSpec(type, appId);
            if (StrUtil.isBlank(specText)) {
                // 还没生成过：用 app 表信息兜底（至少给出初始需求）
                specText = defaultHeader(appId);
            }
            String index = buildFileIndex(type, appId);
            boolean specMeaningful = specText.contains("初始需求：") || specText.contains(HEADER_MARK);
            if (!specMeaningful && StrUtil.isBlank(index)) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【应用现状（供理解上下文；代码细节请用 readFile 查看）】\n");
            if (specMeaningful) {
                sb.append(specText.trim()).append("\n");
            }
            if (StrUtil.isNotBlank(index)) {
                sb.append("\n现有代码文件：\n").append(index);
            }
            String section = sb.toString().trim();
            log.info("[spec] 注入应用现状 {} 字符，appId={}", section.length(), appId);
            return section;
        } catch (RuntimeException e) {
            log.warn("[spec] 构造应用现状失败，appId={}", appId, e);
            return "";
        }
    }

    /**
     * 追加一条变更记录（增量、确定性，不调用模型）。
     * 所有会改动工程代码的入口都应调用本方法，保证摘要不滞后于代码。
     *
     * @param type    代码生成类型
     * @param appId   应用 id
     * @param kind    变更类型（生成 / 可视化修改 / 构建修复 / 手动编辑）
     * @param summary 变更摘要（单行即可）
     */
    public void recordChange(CodeGenTypeEnum type, Long appId, String kind, String summary) {
        if (type == null || appId == null || appId <= 0 || StrUtil.isBlank(summary)) {
            return;
        }
        try {
            SpecDoc doc = readSpec(type, appId);
            List<String> entries = new ArrayList<>(doc.entries());
            entries.add("- " + LocalDateTime.now().format(TS)
                    + " [" + (kind == null ? "变更" : kind) + "] " + oneLine(summary));
            if (entries.size() > MAX_CHANGE_ENTRIES) {
                entries = new ArrayList<>(entries.subList(entries.size() - MAX_CHANGE_ENTRIES, entries.size()));
            }
            writeSpec(type, appId, doc.header(), entries);
        } catch (RuntimeException e) {
            // 规格维护失败不影响生成主流程
            log.warn("[spec] 记录变更失败，appId={}", appId, e);
        }
    }

    /**
     * 规格文档：头部（应用信息）+ 变更记录条目
     */
    private record SpecDoc(String header, List<String> entries) {
    }

    private SpecDoc readSpec(CodeGenTypeEnum type, Long appId) {
        File file = specFile(type, appId);
        if (!file.exists()) {
            return new SpecDoc(defaultHeader(appId), List.of());
        }
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        int idx = content.indexOf(HEADER_MARK);
        if (idx < 0) {
            return new SpecDoc(content.trim() + "\n", List.of());
        }
        String header = content.substring(0, idx).trim() + "\n";
        List<String> entries = new ArrayList<>();
        for (String line : content.substring(idx + HEADER_MARK.length()).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                entries.add(trimmed);
            }
        }
        return new SpecDoc(header, entries);
    }

    private void writeSpec(CodeGenTypeEnum type, Long appId, String header, List<String> entries) {
        File file = specFile(type, appId);
        FileUtil.mkParentDirs(file);
        StringBuilder sb = new StringBuilder();
        sb.append(header == null ? defaultHeader(appId) : header);
        sb.append("\n").append(HEADER_MARK).append("\n");
        for (String entry : entries) {
            sb.append(entry).append("\n");
        }
        FileUtil.writeString(sb.toString(), file, StandardCharsets.UTF_8);
    }

    /**
     * 读取原始规格文件内容；不存在返回 ""
     */
    private String readRawSpec(CodeGenTypeEnum type, Long appId) {
        File file = specFile(type, appId);
        return file.exists() ? FileUtil.readString(file, StandardCharsets.UTF_8) : "";
    }

    private File specFile(CodeGenTypeEnum type, Long appId) {
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        return Paths.get(projectDir, AGENT_DIR, SPEC_FILE).toFile();
    }

    /**
     * 默认头部：应用类型 + 初始需求（来自 app 表；查不到则留空）
     */
    private String defaultHeader(Long appId) {
        StringBuilder sb = new StringBuilder("# 应用规格\n\n");
        try {
            App app = appMapper.selectOneById(appId);
            if (app != null) {
                if (StrUtil.isNotBlank(app.getCodeGenType())) {
                    sb.append("类型：").append(app.getCodeGenType()).append("\n");
                }
                if (StrUtil.isNotBlank(app.getInitPrompt())) {
                    sb.append("初始需求：").append(oneLine(app.getInitPrompt())).append("\n");
                }
            }
        } catch (RuntimeException e) {
            log.warn("[spec] 读取应用信息失败，appId={}", appId, e);
        }
        return sb.toString();
    }

    /**
     * 代码文件索引（相对路径清单），排除 .agent / node_modules / dist 等
     */
    private String buildFileIndex(CodeGenTypeEnum type, Long appId) {
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        File root = new File(projectDir);
        if (!root.isDirectory()) {
            return "";
        }
        List<String> paths = new ArrayList<>();
        collect(root, root, paths);
        if (paths.isEmpty()) {
            return "";
        }
        Collections.sort(paths);
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("- ").append(path).append("\n");
        }
        return sb.toString();
    }

    private void collect(File root, File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if ("node_modules".equals(name) || "dist".equals(name)
                        || AGENT_DIR.equals(name) || ".git".equals(name)) {
                    continue;
                }
                collect(root, child, out);
            } else {
                out.add(relativePath(root, child));
            }
        }
    }

    private String relativePath(File root, File file) {
        String rel = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                .toString().replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    /**
     * 压成单行（变更记录一条一行，避免破坏文件结构）
     */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
