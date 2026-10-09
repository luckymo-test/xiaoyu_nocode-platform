package com.mofanyi.ai;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 代码生成 Prompt 注册表（plan-refactor 7.1 拆分第 S4 步）。
 *
 * <p>把原本逐个注入到 {@code VueProjectAgent} 的 6 个 prompt Bean 收拢到一处：
 * 调用方只需按语义取用（{@link #system(CodeGenTypeEnum)} / {@link #vueFix()} / {@link #modify()} /
 * {@link #renderFix()}），不再关心 Bean 名字与注入姿势。
 *
 * <p>Prompt 内容仍是<b>静态前缀</b>（前缀缓存友好）——见 {@code CodeGenPromptConfig} 的说明。
 */
@Component
public class CodeGenPromptRegistry {

    private final String singleHtmlPrompt;
    private final String multiFilePrompt;
    private final String vuePrompt;
    private final String vueFixPrompt;
    private final String modifyPrompt;
    private final String renderFixPrompt;

    public CodeGenPromptRegistry(String singleHtmlPrompt,
                                 String multiFilePrompt,
                                 @Qualifier("vuePrompt") String vuePrompt,
                                 @Qualifier("vueFixPrompt") String vueFixPrompt,
                                 @Qualifier("modifyPrompt") String modifyPrompt,
                                 @Qualifier("renderFixPrompt") String renderFixPrompt) {
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.vuePrompt = vuePrompt;
        this.vueFixPrompt = vueFixPrompt;
        this.modifyPrompt = modifyPrompt;
        this.renderFixPrompt = renderFixPrompt;
    }

    /**
     * 按代码生成类型取生成用的 system prompt
     */
    public String system(CodeGenTypeEnum type) {
        return switch (type) {
            case HTML -> singleHtmlPrompt;
            case MULTI_FILE -> multiFilePrompt;
            case VUE -> vuePrompt;
        };
    }

    /**
     * Vue 构建失败修复用的 prompt
     */
    public String vueFix() {
        return vueFixPrompt;
    }

    /**
     * 可视化修改用的 prompt
     */
    public String modify() {
        return modifyPrompt;
    }

    /**
     * 渲染问题修复用的 prompt
     */
    public String renderFix() {
        return renderFixPrompt;
    }
}
