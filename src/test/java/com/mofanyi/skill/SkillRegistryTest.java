package com.mofanyi.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry();

    @Test
    @DisplayName("技能按 Profile 解析并去重")
    void resolvesCompatibleSkills() {
        var skills = registry.resolve(
                List.of("seo-audit", "accessible-forms", "seo-audit"), "html-static");
        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(skill -> skill.id().equals("seo-audit")));
    }

    @Test
    @DisplayName("技能与 Profile 不兼容时拒绝")
    void rejectsIncompatibleSkill() {
        assertThrows(RuntimeException.class,
                () -> registry.resolve(List.of("gsap-motion"), "html-static"));
    }
}
