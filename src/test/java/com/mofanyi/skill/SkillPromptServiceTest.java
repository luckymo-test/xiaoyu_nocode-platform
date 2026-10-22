package com.mofanyi.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPromptServiceTest {

    @Test
    @DisplayName("技能版本、权限和验收规则进入 Prompt")
    void buildsSkillPromptSection() {
        SkillRegistry registry = new SkillRegistry();
        SkillContext context = new SkillContext();
        List<SkillDefinition> skills = registry.resolve(
                List.of("accessible-forms"), "html-static");
        context.activate(9L, skills);

        String prompt = new SkillPromptService(context).buildSection(9L);

        assertTrue(prompt.contains("accessible-forms@1"));
        assertTrue(prompt.contains("label 关联"));
        context.deactivate(9L, skills);
    }
}
