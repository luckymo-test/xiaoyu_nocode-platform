package com.mofanyi.ai.memory;

import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link AppSpecService} 单元测试：规格摘要 + 代码库索引。
 * 用临时目录模拟工程目录，不依赖真实 Redis / MySQL。
 */
@ExtendWith(MockitoExtension.class)
class AppSpecServiceTest {

    private static final long APP_ID = 42L;

    @TempDir
    Path projectDir;

    @Mock
    private CodeFileWriteService codeFileWriteService;

    @Mock
    private AppMapper appMapper;

    private AppSpecService appSpecService;

    @BeforeEach
    void setUp() {
        appSpecService = new AppSpecService(codeFileWriteService, appMapper);
        lenient().when(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, APP_ID))
                .thenReturn(projectDir.toString());
    }

    @Test
    @DisplayName("既无规格也无文件时返回空串（调用方据此跳过注入）")
    void buildSpecSection_emptyWhenNothing() {
        assertTrue(appSpecService.buildSpecSection(CodeGenTypeEnum.HTML, APP_ID).isEmpty());
    }

    @Test
    @DisplayName("记录变更后：规格含初始需求与变更记录，索引含代码文件、且不含 .agent")
    void buildSpecSection_afterChange() throws IOException {
        when(appMapper.selectOneById(APP_ID)).thenReturn(App.builder()
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                .initPrompt("做一个登录页")
                .build());
        Files.writeString(projectDir.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);

        appSpecService.recordChange(CodeGenTypeEnum.HTML, APP_ID, "生成", "已完成html生成。产出文件：index.html。");
        String section = appSpecService.buildSpecSection(CodeGenTypeEnum.HTML, APP_ID);

        assertTrue(section.contains("初始需求：做一个登录页"), "应带出初始需求");
        assertTrue(section.contains("## 变更记录"), "应包含变更记录分节");
        assertTrue(section.contains("[生成]"), "应记录变更类型");
        assertTrue(section.contains("- index.html"), "应列出代码文件索引");
        assertFalse(section.contains("spec.md"), ".agent 目录不应出现在索引里");
        assertFalse(section.contains(".agent"), ".agent 目录不应出现在索引里");
    }

    @Test
    @DisplayName("变更记录超过上限时丢弃最旧（只保留最近 N 条）")
    void recordChange_trimsOldEntries() throws IOException {
        for (int i = 0; i < 25; i++) {
            appSpecService.recordChange(CodeGenTypeEnum.HTML, APP_ID, "生成", "第 " + i + " 次变更");
        }

        List<String> lines = Files.readAllLines(projectDir.resolve(".agent/spec.md"), StandardCharsets.UTF_8);
        long entryCount = lines.stream().filter(l -> l.startsWith("- ")).count();

        assertEquals(20, entryCount, "应只保留最近 20 条变更记录");
        assertFalse(String.join("\n", lines).contains("第 0 次变更"), "最旧的记录应被丢弃");
        assertTrue(String.join("\n", lines).contains("第 24 次变更"), "最近的记录应保留");
    }

    @Test
    @DisplayName("非法入参安全返回空串 / 不抛异常")
    void invalidArgs_areSafe() {
        assertTrue(appSpecService.buildSpecSection(null, APP_ID).isEmpty());
        assertTrue(appSpecService.buildSpecSection(CodeGenTypeEnum.HTML, null).isEmpty());
        appSpecService.recordChange(null, APP_ID, "生成", "x");
        appSpecService.recordChange(CodeGenTypeEnum.HTML, APP_ID, "生成", "  ");
    }
}
