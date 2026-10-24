package com.mofanyi.job;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.runtime.StackProfile;
import com.mofanyi.runtime.StackProfileRegistry;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.skill.SkillDefinition;
import com.mofanyi.skill.SkillRegistry;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 校验 Job 创建时固化的运行时、模板和技能版本。
 */
@Component
@Conditional(GenerationExecutionCondition.class)
public class GenerationRuntimeSnapshotValidator {

    private final GenerationJobService jobService;
    private final StackProfileRegistry stackProfiles;
    private final SkillRegistry skills;

    public GenerationRuntimeSnapshotValidator(GenerationJobService jobService,
                                              StackProfileRegistry stackProfiles,
                                              SkillRegistry skills) {
        this.jobService = jobService;
        this.stackProfiles = stackProfiles;
        this.skills = skills;
    }

    public StackProfile validateProfile(GenerationJob job) {
        ThrowUtils.throwIf(job == null || StrUtil.isBlank(job.getStackProfileId()),
                ErrorCode.OPERATION_ERROR, "Job 缺少 StackProfile");
        StackProfile profile = stackProfiles.require(job.getStackProfileId());
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(job.getCodeGenType());
        ThrowUtils.throwIf(type == null || profile.compatibilityType() != type,
                ErrorCode.OPERATION_ERROR, "StackProfile 与 Job 生成类型不兼容");
        ThrowUtils.throwIf(StrUtil.isNotBlank(job.getStackProfileVersion())
                        && !job.getStackProfileVersion().equals(profile.version()),
                ErrorCode.OPERATION_ERROR, "StackProfile 版本已变化，拒绝执行旧 Job");
        return profile;
    }

    public List<SkillDefinition> validateAndLoadSkills(GenerationJob job) {
        List<String> ids = jobService.readSkillIds(job.getSkillsJson());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, String> expectedVersions = jobService.readSkillVersions(
                job.getSkillsSnapshotJson());
        return ids.stream()
                .map(id -> {
                    SkillDefinition skill = skills.get(id);
                    ThrowUtils.throwIf(skill == null, ErrorCode.OPERATION_ERROR,
                            "Job 中的技能已不存在：" + id);
                    String expected = expectedVersions.get(id);
                    ThrowUtils.throwIf(expected != null && !expected.equals(
                                    StrUtil.blankToDefault(skill.version(), "")),
                            ErrorCode.OPERATION_ERROR, "技能版本已变化，拒绝执行旧 Job：" + id);
                    ThrowUtils.throwIf(!skill.compatibleProfiles().contains(
                                    job.getStackProfileId()),
                            ErrorCode.OPERATION_ERROR, "技能与 StackProfile 不兼容：" + id);
                    return skill;
                })
                .toList();
    }
}
