package com.mofanyi.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillContextTest {

    @Test
    @DisplayName("多个 Skill 的工具能力取交集")
    void intersectsAllowedTools() {
        SkillContext context = new SkillContext();
        context.activate(7L, List.of(
                skill("one", Set.of("readFile", "writeFile", "searchImage")),
                skill("two", Set.of("readFile", "writeFile", "diagram"))));

        assertEquals(Set.of("readFile", "writeFile"),
                context.allowedToolIntersection(7L).orElseThrow());
    }

    @Test
    @DisplayName("没有激活 Skill 时不裁剪工具")
    void noSkillMeansNoRestriction() {
        SkillContext context = new SkillContext();
        assertTrue(context.allowedToolIntersection(8L).isEmpty());
    }

    private static SkillDefinition skill(String id, Set<String> tools) {
        return new SkillDefinition(
                id, "1", id, Set.of("html-static"), tools,
                Set.of(), "", List.of(), Set.of());
    }
}
