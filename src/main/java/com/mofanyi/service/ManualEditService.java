package com.mofanyi.service;

import cn.hutool.core.util.EscapeUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.memory.AppSpecService;
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
 * 手动编辑服务：在可视化编辑里手动产生的两种改动，均以带标记的块写进源码，预览/下载/部署同源生效：
 * <ul>
 *   <li>style：CSS 覆盖规则追加进 css/styles.css（/* @user-edit:*\/ … /* @end-user-edit *\/）</li>
 *   <li>text ：文字内容以运行时脚本覆盖，注入 index.html 的 &lt;/body&gt; 前（HTML 注释同款标记）</li>
 * </ul>
 * 支持逐条查询 / 按 id 撤销 / 清空。
 */
@Service
public class ManualEditService {

    private static final String CSS_FILE = "css/styles.css";
    private static final String HTML_FILE = "index.html";
    private static final String KIND_STYLE = "style";
    private static final String KIND_TEXT = "text";

    private static final String UUID_RE = "[0-9a-fA-F-]{36}";

    /**
     * css 里的标记块
     */
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "/\\* @user-edit:(" + UUID_RE + ") \\*/\\r?\\n([\\s\\S]*?)\\r?\\n/\\* @end-user-edit \\*/");

    /**
     * index.html 里的文字覆盖标记块：注释 + &lt;script data-uet …&gt;
     */
    private static final Pattern TEXT_BLOCK = Pattern.compile(
            "<!-- @user-edit:(" + UUID_RE + ") -->\\s*<script data-uet data-id=\"[^\"]*\" data-sel=\"([^\"]*)\" data-text=\"([^\"]*)\"[^>]*>[\\s\\S]*?</script>\\s*<!-- @end-user-edit -->");

    private static final Pattern ANY_BLOCK = Pattern.compile(
            "(?:/\\*|<!--) @user-edit:(" + UUID_RE + ") (?:\\*/|-->)[\\s\\S]*?(?:/\\*|<!--) @end-user-edit (?:\\*/|-->)");

    private static final int MAX_CSS_LEN = 3000;
    private static final int MAX_TEXT_LEN = 2000;

    private final CodeFileWriteService codeFileWriteService;
    private final AppSpecService appSpecService;

    public ManualEditService(CodeFileWriteService codeFileWriteService, AppSpecService appSpecService) {
        this.codeFileWriteService = codeFileWriteService;
        this.appSpecService = appSpecService;
    }

    /**
     * 单条手动修改：kind=style(样式覆盖) / text(文字覆盖)
     */
    public record EditItem(String id, String kind, String selector, String content) {
    }

    /**
     * 用户手动修改快照：把工程里所有 {@code @user-edit} 标记块原样摘出来，供重新生成后重放。
     * 为什么要它：重新生成是 {@code writeFile} 整文件覆盖，会把用户手动改动一起抹掉（plan-refactor 6.2）。
     */
    public record UserEditSnapshot(List<String> cssBlocks, List<String> htmlBlocks) {

        public boolean isEmpty() {
            return (cssBlocks == null || cssBlocks.isEmpty())
                    && (htmlBlocks == null || htmlBlocks.isEmpty());
        }
    }

    /**
     * 生成前快照：摘出当前工程里的全部手动修改块
     */
    public UserEditSnapshot snapshot(CodeGenTypeEnum type, Long appId) {
        if (type == null || appId == null || appId <= 0) {
            return new UserEditSnapshot(List.of(), List.of());
        }
        return new UserEditSnapshot(
                extractBlocks(readFile(type, appId, CSS_FILE)),
                extractBlocks(readFile(type, appId, HTML_FILE)));
    }

    /**
     * 生成后重放：把快照里的手动修改块写回。
     *
     * <p><b>幂等</b>——先清掉目标文件里已有的标记块再追加。因为模型可能只重写了部分文件：
     * 未重写的文件里标记块仍在，若直接追加会产生重复。
     *
     * <p>CSS 追加到文件末尾（同优先级下后出现者生效，用户规则覆盖 AI 规则）；
     * HTML 插到 {@code </body>} 之前（保持脚本在文档流末尾执行）。
     */
    public void replay(CodeGenTypeEnum type, Long appId, UserEditSnapshot snapshot) {
        if (type == null || appId == null || appId <= 0 || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if (snapshot.cssBlocks() != null && !snapshot.cssBlocks().isEmpty()) {
            String css = readFile(type, appId, CSS_FILE);
            if (css != null) {
                writeFile(type, appId, CSS_FILE,
                        removeAllBlocks(css) + String.join("\n", snapshot.cssBlocks()) + "\n");
            }
        }
        if (snapshot.htmlBlocks() != null && !snapshot.htmlBlocks().isEmpty()) {
            String html = readFile(type, appId, HTML_FILE);
            if (html != null) {
                String block = "\n" + String.join("\n", snapshot.htmlBlocks()) + "\n";
                writeFile(type, appId, HTML_FILE, insertBeforeBodyClose(removeAllBlocks(html), block));
            }
        }
    }

    /**
     * 摘出全部标记块（原样返回，含首尾标记）
     */
    private static List<String> extractBlocks(String content) {
        if (StrUtil.isBlank(content)) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        Matcher matcher = ANY_BLOCK.matcher(content);
        while (matcher.find()) {
            blocks.add(matcher.group());
        }
        return blocks;
    }

    /**
     * 去掉全部标记块（重放前先清，保证幂等）
     */
    static String removeAllBlocks(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        return ANY_BLOCK.matcher(content).replaceAll("");
    }

    private String readFile(CodeGenTypeEnum type, Long appId, String relPath) {
        return codeFileWriteService.readFile(codeFileWriteService.getProjectDir(type, appId), relPath);
    }

    private void writeFile(CodeGenTypeEnum type, Long appId, String relPath, String content) {
        codeFileWriteService.saveProjectFile(type, appId, relPath, content);
    }

    /**
     * 列出全部手动修改：先 style（css 顺序），后 text（html 顺序）
     */
    public List<EditItem> list(CodeGenTypeEnum type, Long appId) {
        List<EditItem> items = new ArrayList<>();
        String css = readFile(type, appId, CSS_FILE);
        if (StrUtil.isNotBlank(css)) {
            Matcher m = STYLE_BLOCK.matcher(css);
            while (m.find()) {
                String body = m.group(2).trim();
                items.add(new EditItem(m.group(1), KIND_STYLE, extractSelector(body), body));
            }
        }
        String html = readFile(type, appId, HTML_FILE);
        if (StrUtil.isNotBlank(html)) {
            Matcher m = TEXT_BLOCK.matcher(html);
            while (m.find()) {
                String selector = EscapeUtil.unescapeHtml4(m.group(2));
                String text = EscapeUtil.unescapeHtml4(m.group(3));
                items.add(new EditItem(m.group(1), KIND_TEXT, selector, text));
            }
        }
        return items;
    }

    /**
     * 追加一条 style 手动修改
     */
    public EditItem applyStyle(CodeGenTypeEnum type, Long appId, String selector, String css) {
        String rule = (css == null ? "" : css).trim();
        ThrowUtils.throwIf(rule.isEmpty(), ErrorCode.PARAMS_ERROR, "修改内容为空");
        ThrowUtils.throwIf(rule.length() > MAX_CSS_LEN, ErrorCode.PARAMS_ERROR, "修改内容过长");
        checkNoMarker(rule);
        String base = readFile(type, appId, CSS_FILE);
        ThrowUtils.throwIf(StrUtil.isBlank(base),
                ErrorCode.OPERATION_ERROR, "尚未生成样式文件，请先生成");
        String id = UUID.randomUUID().toString();
        String block = "\n/* @user-edit:" + id + " */\n" + rule + "\n/* @end-user-edit */\n";
        writeFile(type, appId, CSS_FILE, base + block);
        String sel = StrUtil.isBlank(selector) ? extractSelector(rule) : selector.trim();
        appSpecService.recordChange(type, appId, "手动编辑", "样式：" + sel);
        return new EditItem(id, KIND_STYLE, sel, rule);
    }

    /**
     * 追加一条 text 手动修改：往 index.html &lt;/body&gt; 前注入带标记的运行时覆盖脚本
     */
    public EditItem applyText(CodeGenTypeEnum type, Long appId, String selector, String text) {
        String newText = text == null ? "" : text.trim();
        ThrowUtils.throwIf(StrUtil.isBlank(selector), ErrorCode.PARAMS_ERROR, "缺少目标元素");
        ThrowUtils.throwIf(newText.isEmpty(), ErrorCode.PARAMS_ERROR, "文字内容为空");
        ThrowUtils.throwIf(newText.length() > MAX_TEXT_LEN, ErrorCode.PARAMS_ERROR, "文字内容过长");
        checkNoMarker(newText);
        String html = readFile(type, appId, HTML_FILE);
        ThrowUtils.throwIf(StrUtil.isBlank(html),
                ErrorCode.OPERATION_ERROR, "尚未生成页面文件，请先生成");
        String id = UUID.randomUUID().toString();
        // 属性值做 HTML 转义，脚本运行时不解析，用 DOM 读属性即可还原原文字
        String selAttr = EscapeUtil.escapeHtml4(selector);
        String textAttr = EscapeUtil.escapeHtml4(newText);
        String block = "\n<!-- @user-edit:" + id + " -->\n"
                + "<script data-uet data-id=\"" + id + "\" data-sel=\"" + selAttr
                + "\" data-text=\"" + textAttr
                + "\">(function(){var s=document.currentScript;var e=document.querySelector(s.getAttribute('data-sel'));"
                + "if(e){e.textContent=s.getAttribute('data-text');}})();</script>\n"
                + "<!-- @end-user-edit -->\n";
        String newHtml = insertBeforeBodyClose(html, block);
        writeFile(type, appId, HTML_FILE, newHtml);
        appSpecService.recordChange(type, appId, "手动编辑", "文字：" + selector.trim() + " → " + newText);
        return new EditItem(id, KIND_TEXT, selector.trim(), newText);
    }

    /**
     * 按 id 撤销某条手动修改（先 css 后 html）；不存在返回 false
     */
    public boolean undo(CodeGenTypeEnum type, Long appId, String id) {
        ThrowUtils.throwIf(StrUtil.isBlank(id), ErrorCode.PARAMS_ERROR, "缺少修改 id");
        String css = readFile(type, appId, CSS_FILE);
        if (StrUtil.isNotBlank(css)) {
            String stripped = removeBlockById(css, id);
            if (stripped != null) {
                writeFile(type, appId, CSS_FILE, stripped);
                return true;
            }
        }
        String html = readFile(type, appId, HTML_FILE);
        if (StrUtil.isNotBlank(html)) {
            String stripped = removeBlockById(html, id);
            if (stripped != null) {
                writeFile(type, appId, HTML_FILE, stripped);
                return true;
            }
        }
        return false;
    }

    /**
     * 清空全部手动修改（style + text），返回移除条数
     */
    public int clear(CodeGenTypeEnum type, Long appId) {
        int count = 0;
        String css = readFile(type, appId, CSS_FILE);
        if (StrUtil.isNotBlank(css)) {
            Matcher m = STYLE_BLOCK.matcher(css);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                writeFile(type, appId, CSS_FILE, STYLE_BLOCK.matcher(css).replaceAll(""));
                count += n;
            }
        }
        String html = readFile(type, appId, HTML_FILE);
        if (StrUtil.isNotBlank(html)) {
            Matcher m = TEXT_BLOCK.matcher(html);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                writeFile(type, appId, HTML_FILE, TEXT_BLOCK.matcher(html).replaceAll(""));
                count += n;
            }
        }
        return count;
    }

    /**
     * 按 id 去掉一个标记块；找到返回新内容，未找到返回 null
     */
    private String removeBlockById(String content, String id) {
        Pattern p = Pattern.compile(
                "(?:/\\*|<!--) @user-edit:" + Pattern.quote(id) + " (?:\\*/|-->)[\\s\\S]*?(?:/\\*|<!--) @end-user-edit (?:\\*/|-->)");
        Matcher m = p.matcher(content);
        return m.find() ? m.replaceFirst("") : null;
    }

    private String insertBeforeBodyClose(String html, String block) {
        int idx = html.toLowerCase().lastIndexOf("</body>");
        if (idx < 0) {
            return html + block;
        }
        return html.substring(0, idx) + block + html.substring(idx);
    }

    private void checkNoMarker(String s) {
        ThrowUtils.throwIf(s.contains("@user-edit") || s.contains("@end-user-edit"),
                ErrorCode.PARAMS_ERROR, "非法修改内容");
    }

    /**
     * 从一条 CSS 规则文本里提取选择器（第一个 '{' 之前的内容首行）
     */
    private String extractSelector(String css) {
        String firstLine = css.split("\\{", 2)[0].trim().replace("\n", " ").replaceAll("\\s+", " ");
        return firstLine.isEmpty() ? css : firstLine;
    }
}
