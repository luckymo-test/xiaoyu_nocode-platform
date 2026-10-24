package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.model.vo.StackProfileVO;
import com.mofanyi.runtime.CommandSpec;
import com.mofanyi.runtime.StackProfile;
import com.mofanyi.runtime.StackProfileRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/stack-profiles")
public class StackProfileController {

    private final StackProfileRegistry registry;

    public StackProfileController(StackProfileRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public BaseResponse<List<StackProfileVO>> list() {
        return ResultUtils.success(registry.list().stream().map(this::toVO).toList());
    }

    private StackProfileVO toVO(StackProfile profile) {
        StackProfileVO vo = new StackProfileVO();
        vo.setId(profile.id());
        vo.setVersion(profile.version());
        vo.setDisplayName(profile.displayName());
        vo.setCompatibilityType(profile.compatibilityType().getValue());
        vo.setCapabilities(profile.capabilities().stream().sorted().toList());
        vo.setAvailable(profile.available());
        vo.setUnavailableReason(profile.unavailableReason());
        vo.setInstallCommands(profile.installCommands().stream().map(CommandSpec::executable).toList());
        vo.setBuildCommands(profile.buildCommands().stream().map(CommandSpec::executable).toList());
        vo.setOutputDirectory(profile.outputDirectory());
        return vo;
    }
}
