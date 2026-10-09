package com.mofanyi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 缓存校验（plan-agent-engineering 第七章）。
 *
 * <p>Prompt 文件是**静态前缀**：一旦含时间戳 / UUID / 会话变量，每轮前缀都变，
 * 模型侧的前缀缓存全部失效（成本与时延直接上去）。这个测试把它钉死。
 */
class CodeGenPromptStaticTest {

    private static final List<String> PROMPTS = List.of(
            "codegen_singlehtml_prompt.txt",
            "codegen_multifile_prompt.txt",
            "codegen_vue_prompt.txt",
            "codegen_vue_fix_prompt.txt",
            "codegen_modify_prompt.txt",
            "codegen_render_fix_prompt.txt");

    /**
     * 疑似动态内容：日期 / 时间 / UUID / 会话变量
     */
    private static final Pattern DYNAMIC = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{2}:\\d{2}:\\d{2}"
                    + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "|当前时间|今天是|会话\\s*ID|sessionId|session_id|appId\\s*=");

    @Test
    @DisplayName("Prompt 必须保持静态：不含时间戳/UUID/会话变量")
    void promptsMustBeStatic() throws IOException {
        for (String name : PROMPTS) {
            String text = readPrompt(name);
            assertNotNull(text, name + " 未找到");
            Matcher matcher = DYNAMIC.matcher(text);
            if (matcher.find()) {
                assertFalse(true, name + " 含疑似动态内容（会破坏前缀缓存）：" + matcher.group());
            }
        }
    }

    @Test
    @DisplayName("Prompt 文件非空且加载正常")
    void promptsAreLoadable() throws IOException {
        for (String name : PROMPTS) {
            String text = readPrompt(name);
            assertNotNull(text, name + " 未找到");
            assertTrue(text.trim().length() > 100, name + " 内容过短，疑似加载失败");
        }
    }

    private String readPrompt(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompt/" + name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
