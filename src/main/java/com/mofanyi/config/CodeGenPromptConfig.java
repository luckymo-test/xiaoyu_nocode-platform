package com.mofanyi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 代码生成 Prompt 注册表：把 prompt/*.txt 加载成 String Bean 供 agent 注入。
 *
 * <p>注意（前缀缓存友好，见 plan-agent-engineering 第七章）：这些 prompt 是<b>静态前缀</b>，
 * 必须保持内容不含时间戳 / 随机数 / 会话变量等动态内容——否则每轮前缀都变，模型侧的前缀缓存全部失效。
 * 动态内容（对话记忆、应用现状、用户输入）一律排在静态前缀之后注入。
 */
@Configuration
public class CodeGenPromptConfig {

    @Bean
    public String singleHtmlPrompt() {
        return loadPrompt("prompt/codegen_singlehtml_prompt.txt");
    }

    @Bean
    public String multiFilePrompt() {
        return loadPrompt("prompt/codegen_multifile_prompt.txt");
    }

    @Bean
    public String vuePrompt() {
        return loadPrompt("prompt/codegen_vue_prompt.txt");
    }

    @Bean
    public String vueFixPrompt() {
        return loadPrompt("prompt/codegen_vue_fix_prompt.txt");
    }

    @Bean
    public String modifyPrompt() {
        return loadPrompt("prompt/codegen_modify_prompt.txt");
    }

    @Bean
    public String renderFixPrompt() {
        return loadPrompt("prompt/codegen_render_fix_prompt.txt");
    }

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("无法加载 Prompt 文件: " + path, e);
        }
    }
}
