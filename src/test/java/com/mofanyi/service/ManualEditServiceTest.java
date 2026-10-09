package com.mofanyi.service;

import com.mofanyi.ai.memory.AppSpecService;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动修改的"快照 + 重放"测试（plan-refactor 6.2 方案 B）：
 * 重新生成是整文件覆盖，用户手动改动不能因此丢失。
 */
@ExtendWith(MockitoExtension.class)
class ManualEditServiceTest {

    private static final long APP_ID = 9L;
    private static final CodeGenTypeEnum TYPE = CodeGenTypeEnum.HTML;

    @TempDir
    Path tmpRoot;

    @Mock
    private AppSpecService appSpecService;

    private CodeFileWriteService files;
    private ManualEditService service;

    @BeforeEach
    void setUp() {
        files = new CodeFileWriteService();
        files.setTmpRoot(tmpRoot.toString());
        service = new ManualEditService(files, appSpecService);
        seedProject();
    }

    private void seedProject() {
        files.saveProjectFile(TYPE, APP_ID, "index.html",
                "<html><body><h1>标题</h1></body></html>");
        files.saveProjectFile(TYPE, APP_ID, "css/styles.css", "h1{color:red}");
    }

    private String readCss() {
        return files.readFile(files.getProjectDir(TYPE, APP_ID), "css/styles.css");
    }

    private String readHtml() {
        return files.readFile(files.getProjectDir(TYPE, APP_ID), "index.html");
    }

    @Test
    @DisplayName("核心场景：重新生成整文件覆盖后，重放能把手动修改找回来")
    void replay_restoresEditsAfterOverwrite() {
        service.applyStyle(TYPE, APP_ID, "h1", "h1{color:blue}");
        ManualEditService.UserEditSnapshot snapshot = service.snapshot(TYPE, APP_ID);
        assertFalse(snapshot.isEmpty());

        // 模拟重新生成：模型把 styles.css 整个覆盖掉
        files.saveProjectFile(TYPE, APP_ID, "css/styles.css", "/* AI 重新生成 */\nh1{color:green}");

        service.replay(TYPE, APP_ID, snapshot);

        String css = readCss();
        assertTrue(css.contains("h1{color:blue}"), "用户手动修改应被找回");
        assertTrue(css.contains("h1{color:green}"), "AI 新写的内容应保留");
    }

    @Test
    @DisplayName("重放是幂等的：模型没重写该文件时不会产生重复块")
    void replay_isIdempotent() {
        service.applyStyle(TYPE, APP_ID, "h1", "h1{color:blue}");
        ManualEditService.UserEditSnapshot snapshot = service.snapshot(TYPE, APP_ID);

        service.replay(TYPE, APP_ID, snapshot);
        service.replay(TYPE, APP_ID, snapshot);

        assertEquals(1, countOccurrences(readCss(), "@user-edit:"), "重复重放不应产生重复块");
    }

    @Test
    @DisplayName("HTML 文字覆盖重放时插在 </body> 之前")
    void replay_insertsHtmlBlockBeforeBodyClose() {
        service.applyText(TYPE, APP_ID, "h1", "新标题");
        ManualEditService.UserEditSnapshot snapshot = service.snapshot(TYPE, APP_ID);

        files.saveProjectFile(TYPE, APP_ID, "index.html", "<html><body><h1>B</h1></body></html>");
        service.replay(TYPE, APP_ID, snapshot);

        String html = readHtml();
        assertTrue(html.contains("@user-edit:"), "文字覆盖块应被找回");
        assertTrue(html.indexOf("@user-edit:") < html.toLowerCase().lastIndexOf("</body>"),
                "标记块应位于 </body> 之前");
    }

    @Test
    @DisplayName("没有手动修改时快照为空，重放是空操作")
    void snapshot_emptyWhenNoEdits() {
        ManualEditService.UserEditSnapshot snapshot = service.snapshot(TYPE, APP_ID);
        assertTrue(snapshot.isEmpty());

        service.replay(TYPE, APP_ID, snapshot);
        assertEquals("h1{color:red}", readCss());
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = text.indexOf(token);
        while (idx >= 0) {
            count++;
            idx = text.indexOf(token, idx + token.length());
        }
        return count;
    }
}
