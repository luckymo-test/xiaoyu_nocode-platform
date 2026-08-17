package com.mofanyi.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

@Configuration
public class AiServiceFactory {

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

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("无法加载 Prompt 文件: " + path, e);
        }
    }
}
