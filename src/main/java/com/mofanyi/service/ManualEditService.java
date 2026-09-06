package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手动样式编辑服务：把用户在可视化编辑里手动生成的 CSS 覆盖规则，
 * 以带标记的块追加进工程 css/styles.css（预览/下载/部署都读该文件，天然生效）。
 * 支持逐条查询/按 id 撤销/清空；改动与 AI 生成内容混在同一 css 文件，撤销只删标记块。
 */
@Service
public class ManualEditService {

    /**
     * 样式文件相对工程根路径（html / multi_file 通用）
     */
    private static final String CSS_FILE = "css/styles.css";

    /**
     * 手动编辑块：/* @user-edit:<uuid> *\/ ... /* @end-user-edit *\/
     */
    private static final Pattern EDIT_BLOCK = Pattern.compile(
            "/\\* @user-edit:([0-9a-fA-F-]{36}) \\*/\\r?\\n([\\s\\S]*?)\\r?\\n/\\* @end-user-edit \\*/");

    private static final int MAX_CSS_LEN = 3000;

    private final CodeFileWriteService codeFileWriteService;

    public ManualEditService(CodeFileWriteService codeFileWriteService) {
        this.codeFileWriteService = codeFileWriteService;
    }

    /**
     * 单条手动修改
     */
    public record EditItem(String id, String selector, String css) {
    }

    private String readCss(CodeGenTypeEnum type, Long appId) {
        return codeFileWriteService.readFile(codeFileWriteService.getProjectDir(type, appId), CSS_FILE);
    }

    private void writeCss(CodeGenTypeEnum type, Long appId, String content) {
        codeFileWriteService.saveProjectFile(type, appId, CSS_FILE, content);
    }

    /**
     * 列出当前手动修改（按 css 文件中出现的顺序）
     */
    public List<EditItem> list(CodeGenTypeEnum type, Long appId) {
        String css = readCss(type, appId);
        if (StrUtil.isBlank(css)) {
            return new ArrayList<>();
        }
        List<EditItem> items = new ArrayList<>();
        Matcher m = EDIT_BLOCK.matcher(css);
        while (m.find()) {
            String body = m.group(2).trim();
            items.add(new EditItem(m.group(1), extractSelector(body), body));
        }
        return items;
    }

    /**
     * 追加一条手动修改，返回新条目
     */
    public EditItem apply(CodeGenTypeEnum type, Long appId, String selector, String css) {
        String rule = (css == null ? "" : css).trim();
        ThrowUtils.throwIf(rule.isEmpty(), ErrorCode.PARAMS_ERROR, "修改内容为空");
        ThrowUtils.throwIf(rule.length() > MAX_CSS_LEN, ErrorCode.PARAMS_ERROR, "修改内容过长");
        // 防注入：不能含标记字符串，避免伪造/截断块边界
        ThrowUtils.throwIf(rule.contains("@user-edit") || rule.contains("@end-user-edit"),
                ErrorCode.PARAMS_ERROR, "非法修改内容");
        String base = readCss(type, appId);
        ThrowUtils.throwIf(StrUtil.isBlank(base),
                ErrorCode.OPERATION_ERROR, "尚未生成样式文件，请先生成");
        String id = UUID.randomUUID().toString();
        String block = "\n/* @user-edit:" + id + " */\n" + rule + "\n/* @end-user-edit */\n";
        writeCss(type, appId, base + block);
        String sel = StrUtil.isBlank(selector) ? extractSelector(rule) : selector.trim();
        return new EditItem(id, sel, rule);
    }

    /**
     * 按 id 撤销某条手动修改；不存在返回 false
     */
    public boolean undo(CodeGenTypeEnum type, Long appId, String id) {
        ThrowUtils.throwIf(StrUtil.isBlank(id), ErrorCode.PARAMS_ERROR, "缺少修改 id");
        String css = readCss(type, appId);
        if (StrUtil.isBlank(css)) {
            return false;
        }
        Matcher m = EDIT_BLOCK.matcher(css);
        StringBuffer sb = new StringBuffer();
        boolean removed = false;
        while (m.find()) {
            if (id.equals(m.group(1))) {
                removed = true;
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        if (removed) {
            writeCss(type, appId, sb.toString());
        }
        return removed;
    }

    /**
     * 清空所有手动修改，返回移除条数
     */
    public int clear(CodeGenTypeEnum type, Long appId) {
        String css = readCss(type, appId);
        if (StrUtil.isBlank(css)) {
            return 0;
        }
        Matcher m = EDIT_BLOCK.matcher(css);
        StringBuffer sb = new StringBuffer();
        int count = 0;
        while (m.find()) {
            count++;
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        if (count > 0) {
            writeCss(type, appId, sb.toString());
        }
        return count;
    }

    /**
     * 从一条 CSS 规则文本里提取选择器（第一个 '{' 之前的内容首行）
     */
    private String extractSelector(String css) {
        String firstLine = css.split("\\{", 2)[0].trim().replace("\n", " ").replaceAll("\\s+", " ");
        return firstLine.isEmpty() ? css : firstLine;
    }
}
