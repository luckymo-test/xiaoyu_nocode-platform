package com.mofanyi.workflow;

import com.mofanyi.entity.GenerationJob;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.service.GenerationJobEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentOrchestratorTest {

    @Test
    @DisplayName("任务终态失败时把运行中的 Agent Step 标记为 FAILED")
    void marksRunningStepFailed() {
        WorkflowPlanService plans = mock(WorkflowPlanService.class);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                plans, mock(GenerationJobMapper.class),
                mock(GenerationJobEventService.class));
        GenerationJob job = GenerationJob.builder()
                .id(99L)
                .workflowVersion(2)
                .build();

        orchestrator.markFailed(job, "MAX_STEPS_EXCEEDED", "round limit reached");

        verify(plans).failRunning(99L, "MAX_STEPS_EXCEEDED", "round limit reached", false);
    }

    @Test
    @DisplayName("Step 审计写入失败不应阻止 Job 进入失败终态")
    void stepAuditFailureDoesNotEscape() {
        WorkflowPlanService plans = mock(WorkflowPlanService.class);
        doThrow(new IllegalStateException("db unavailable"))
                .when(plans).failRunning(99L, "BUILD", "failed", false);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                plans, mock(GenerationJobMapper.class),
                mock(GenerationJobEventService.class));
        GenerationJob job = GenerationJob.builder()
                .id(99L)
                .workflowVersion(2)
                .build();

        assertDoesNotThrow(() -> orchestrator.markFailed(job, "BUILD", "failed"));
    }
}
