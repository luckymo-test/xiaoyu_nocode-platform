package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.model.vo.TemplateBundleVO;
import com.mofanyi.template.TemplateBundle;
import com.mofanyi.template.TemplateRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/templates")
public class TemplateController {

    private final TemplateRegistry registry;

    public TemplateController(TemplateRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public BaseResponse<List<TemplateBundleVO>> list() {
        return ResultUtils.success(registry.list().stream().map(this::toVO).toList());
    }

    private TemplateBundleVO toVO(TemplateBundle bundle) {
        TemplateBundleVO vo = new TemplateBundleVO();
        vo.setId(bundle.id());
        vo.setVersion(bundle.version());
        vo.setDisplayName(bundle.displayName());
        vo.setCompatibleProfiles(bundle.compatibleProfiles().stream().sorted().toList());
        vo.setSectionIds(bundle.sectionIds());
        vo.setDesignTokens(bundle.designTokens());
        vo.setPromptFragment(bundle.promptFragment());
        vo.setEffectiveHash(bundle.effectiveHash());
        return vo;
    }
}
