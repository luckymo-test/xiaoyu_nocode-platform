package com.mofanyi.ai;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final String reactPrompt;
    private final String reactFixPrompt;
    private final String nextPrompt;
    private final String nextFixPrompt;
    private final String modifyPrompt;
    private final String renderFixPrompt;

    public CodeGenPromptRegistry(String singleHtmlPrompt,
                                 String multiFilePrompt,
                                 @Qualifier("vuePrompt") String vuePrompt,
                                 @Qualifier("vueFixPrompt") String vueFixPrompt,
                                 @Qualifier("reactPrompt") String reactPrompt,
                                 @Qualifier("reactFixPrompt") String reactFixPrompt,
                                 @Qualifier("nextPrompt") String nextPrompt,
                                 @Qualifier("nextFixPrompt") String nextFixPrompt,
                                 @Qualifier("modifyPrompt") String modifyPrompt,
                                 @Qualifier("renderFixPrompt") String renderFixPrompt) {
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.vuePrompt = vuePrompt;
        this.vueFixPrompt = vueFixPrompt;
        this.reactPrompt = reactPrompt;
        this.reactFixPrompt = reactFixPrompt;
        this.nextPrompt = nextPrompt;
        this.nextFixPrompt = nextFixPrompt;
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
     * Runtime profile takes precedence over the legacy compatibility enum.
     */
    public String system(String stackProfileId, CodeGenTypeEnum type) {
        return switch (stackProfileId) {
            case "react-vite-ts" -> reactPrompt;
            case "next-static-export" -> nextPrompt;
            default -> system(type);
        };
    }

    /**
     * Vue 构建失败修复用的 prompt
     */
    public String vueFix() {
        return vueFixPrompt;
    }

    public String buildFix(String stackProfileId) {
        return switch (stackProfileId) {
            case "react-vite-ts" -> reactFixPrompt;
            case "next-static-export" -> nextFixPrompt;
            default -> vueFixPrompt;
        };
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

    /**
     * Prompt 版本只暴露内容指纹，不把系统提示词正文写入 Revision。
     */
    public Map<String, String> versions() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("html", com.mofanyi.ai.model.ModelExecutionTrace.version(singleHtmlPrompt));
        versions.put("multi-file", com.mofanyi.ai.model.ModelExecutionTrace.version(multiFilePrompt));
        versions.put("vue", com.mofanyi.ai.model.ModelExecutionTrace.version(vuePrompt));
        versions.put("vue-fix", com.mofanyi.ai.model.ModelExecutionTrace.version(vueFixPrompt));
        versions.put("react", com.mofanyi.ai.model.ModelExecutionTrace.version(reactPrompt));
        versions.put("react-fix", com.mofanyi.ai.model.ModelExecutionTrace.version(reactFixPrompt));
        versions.put("next-static", com.mofanyi.ai.model.ModelExecutionTrace.version(nextPrompt));
        versions.put("next-fix", com.mofanyi.ai.model.ModelExecutionTrace.version(nextFixPrompt));
        versions.put("modify", com.mofanyi.ai.model.ModelExecutionTrace.version(modifyPrompt));
        versions.put("render-fix", com.mofanyi.ai.model.ModelExecutionTrace.version(renderFixPrompt));
        return versions;
    }
}
