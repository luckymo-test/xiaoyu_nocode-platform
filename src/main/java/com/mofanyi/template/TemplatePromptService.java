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
        String designTokens = bundle.designTokens().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> "- " + entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                【模板能力包（服务端注册表事实）】
                template=%s version=%s hash=%s
                sections=%s
                designTokens:
                %s
                artDirection=%s
                必须把 designTokens 落实为实际 CSS/Tailwind 设计令牌，而不是只写在注释里。
                模板是视觉与结构建议，不得覆盖用户需求、安全规则或工具约束。
                """.formatted(bundle.id(), bundle.version(), bundle.effectiveHash(),
                String.join(", ", bundle.sectionIds()), designTokens,
                bundle.promptFragment()).trim();
    }
}
