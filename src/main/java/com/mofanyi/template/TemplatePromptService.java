package com.mofanyi.template;

import org.springframework.stereotype.Service;

@Service
public class TemplatePromptService {

    private final TemplateContext context;

    public TemplatePromptService(TemplateContext context) {
        this.context = context;
    }

    public String buildSection(Long appId) {
        TemplateBundle bundle = context.get(appId);
        if (bundle == null) {
            return "";
        }
        return """
                【模板能力包（服务端注册表事实）】
                template=%s version=%s hash=%s
                sections=%s
                designTokens=%s
                artDirection=%s
                模板是视觉与结构建议，不得覆盖用户需求、安全规则或工具约束。
                """.formatted(bundle.id(), bundle.version(), bundle.effectiveHash(),
                String.join(", ", bundle.sectionIds()), bundle.designTokens(),
                bundle.promptFragment()).trim();
    }
}
