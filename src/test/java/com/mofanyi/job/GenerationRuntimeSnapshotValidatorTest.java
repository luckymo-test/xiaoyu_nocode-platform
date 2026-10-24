package com.mofanyi.job;

import com.mofanyi.entity.GenerationJob;
import com.mofanyi.runtime.StackProfileRegistry;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.skill.SkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationRuntimeSnapshotValidatorTest {

    private final GenerationJobService jobService = mock(GenerationJobService.class);
    private final GenerationRuntimeSnapshotValidator validator =
            new GenerationRuntimeSnapshotValidator(
                    jobService, new StackProfileRegistry(), new SkillRegistry());

    @Test
    @DisplayName("StackProfile 版本与 Job 快照不一致时拒绝旧 Job")
    void rejectsChangedStackProfileVersion() {
        assertThrows(RuntimeException.class, () -> validator.validateProfile(job("0")));
        assertDoesNotThrow(() -> validator.validateProfile(job("1")));
    }

    @Test
    @DisplayName("Skill 版本变化时拒绝执行旧 Job")
    void rejectsChangedSkillVersion() {
        GenerationJob job = job("1");
        job.setSkillsJson("[\"seo-audit\"]");
        when(jobService.readSkillIds(job.getSkillsJson())).thenReturn(List.of("seo-audit"));
        when(jobService.readSkillVersions(job.getSkillsSnapshotJson()))
                .thenReturn(Map.of("seo-audit", "0"));

        assertThrows(RuntimeException.class,
                () -> validator.validateAndLoadSkills(job));
    }

    private GenerationJob job(String stackProfileVersion) {
        return GenerationJob.builder()
                .id(99L)
                .appId(7L)
                .codeGenType("html")
                .stackProfileId("html-static")
                .stackProfileVersion(stackProfileVersion)
                .skillsJson("[]")
                .skillsSnapshotJson("[]")
                .build();
    }
}
