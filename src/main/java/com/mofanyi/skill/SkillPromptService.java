package com.mofanyi.skill;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SkillPromptService {

    private final SkillContext context;

    public SkillPromptService(SkillContext context) {
        this.context = context;
    }

    public String buildSection(Long appId) {
        List<SkillDefinition> skills = context.get(appId);
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【技能能力包（服务端注册表事实）】\n");
        for (SkillDefinition skill : skills) {
            sb.append("- ").append(skill.id()).append("@").append(skill.version())
                    .append(": ").append(skill.promptFragment())
                    .append("；验收=").append(String.join("、", skill.validationRules()))
                    .append('\n');
        }
        sb.append("技能不得扩大工具权限；未声明的依赖或工具必须拒绝。");
        return sb.toString();
    }
}
