package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.model.vo.SkillDefinitionVO;
import com.mofanyi.skill.SkillDefinition;
import com.mofanyi.skill.SkillRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/skills")
public class SkillController {

    private final SkillRegistry registry;

    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public BaseResponse<List<SkillDefinitionVO>> list() {
        return ResultUtils.success(registry.list().stream().map(this::toVO).toList());
    }

    private SkillDefinitionVO toVO(SkillDefinition skill) {
        SkillDefinitionVO vo = new SkillDefinitionVO();
        vo.setId(skill.id());
        vo.setVersion(skill.version());
        vo.setDisplayName(skill.displayName());
        vo.setCompatibleProfiles(skill.compatibleProfiles().stream().sorted().toList());
        vo.setAllowedTools(skill.allowedTools().stream().sorted().toList());
        vo.setPermissions(skill.permissions().stream().sorted().toList());
        vo.setPromptFragment(skill.promptFragment());
        vo.setValidationRules(skill.validationRules());
        vo.setConflictsWith(skill.conflictsWith().stream().sorted().toList());
        return vo;
    }
}
