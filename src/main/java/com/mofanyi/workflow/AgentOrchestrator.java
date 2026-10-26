package com.mofanyi.workflow;

import com.mofanyi.core.AiCallContext;
import com.mofanyi.entity.AgentPlan;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.GenerationJobEventService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Workflow v2 entry point. Planning happens before a draft workspace exists;
 * the existing Coder implementation remains shared with legacy jobs.
 */
@Service
public class AgentOrchestrator {

    private final WorkflowPlanService plans;
    private final GenerationJobMapper jobMapper;
    private final GenerationJobEventService events;

    public AgentOrchestrator(WorkflowPlanService plans,
                             GenerationJobMapper jobMapper,
                             GenerationJobEventService events) {
        this.plans = plans;
        this.jobMapper = jobMapper;
        this.events = events;
    }

    public boolean prepareBeforeWorkspace(GenerationJob job,
                                          CodeGenTypeEnum type,
                                          GenerationJobCreateRequest request) {
        if (job.getWorkflowVersion() == null || job.getWorkflowVersion() < 2) {
            return true;
        }
        AiCallContext context = AiCallContext.ofJob(
                job.getAppId(), job.getRequestedBy(), "workflow-plan", job.getId());
        AgentPlan plan = plans.ensureProposal(job, type, request, context);
        if (plan.getConfirmedAt() == null
                && !"CONFIRMED".equals(plan.getStatus())) {
            if (jobMapper.waitForConfirmation(
                    job.getId(), job.getFencingToken(), plan.getPlanHash()) != 1) {
                throw new IllegalStateException("任务无法进入需求确认状态");
            }
            events.append(job.getId(), "plan_ready", "CONFIRMATION",
                    Map.of("planId", plan.getId(), "planHash", plan.getPlanHash()));
            events.append(job.getId(), "confirmation_required", "CONFIRMATION",
                    Map.of("planHash", plan.getPlanHash(),
                            "message", "请确认需求、页面、数据模型与权限方案"));
            return false;
        }
        if (!plan.getPlanHash().equals(job.getConfirmedPlanHash())) {
            throw new IllegalStateException("确认计划与当前任务不一致");
        }
        return true;
    }

    public void startCoder(GenerationJob job) {
        if (isWorkflow(job)) {
            plans.startFirstPending(job.getId(), "CODER");
            emitStep(job, "coder_started", "CODER", "RUNNING");
        }
    }

    public void completeCoder(GenerationJob job, String summary) {
        if (isWorkflow(job)) {
            plans.completeAll(job.getId(), "CODER", summary);
            emitStep(job, "step_completed", "CODER", "SUCCEEDED");
        }
    }

    public void completeReviewer(GenerationJob job, String result) {
        if (isWorkflow(job)) {
            plans.completeAll(job.getId(), "REVIEWER", result);
            emitStep(job, "step_completed", "REVIEWER", "SUCCEEDED");
        }
    }

    public void completeDeploy(GenerationJob job, String result) {
        if (isWorkflow(job)) {
            plans.completeAll(job.getId(), "DEPLOY", result);
            emitStep(job, "step_completed", "DEPLOY", "SUCCEEDED");
        }
    }

    public void markPaused(GenerationJob job, String reason) {
        if (isWorkflow(job)) {
            plans.failRunning(job.getId(), "PAUSED", reason, true);
        }
    }

    private void emitStep(GenerationJob job, String type, String stage, String status) {
        try {
            events.append(job.getId(), type, stage, Map.of("agentType", stage, "status", status));
        } catch (RuntimeException ignored) {
            // The database is authoritative; a transient event write is retried by SSE replay/probes.
        }
    }

    private boolean isWorkflow(GenerationJob job) {
        return job.getWorkflowVersion() != null && job.getWorkflowVersion() >= 2;
    }
}
