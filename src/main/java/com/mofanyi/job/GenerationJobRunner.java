package com.mofanyi.job;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.ai.task.GenerationCancelledException;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.GeneratedResultContentFormatter;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.enums.GenerationJobStatusEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.runtime.StackProfile;
import com.mofanyi.runtime.StackProfileContext;
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.GenerationJobEventService;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.AppRevisionService;
import com.mofanyi.workspace.WorkspaceManager;
import com.mofanyi.workflow.AgentOrchestrator;
import com.mofanyi.runtime.AppRuntimeSchemaService;
import com.mofanyi.service.AppService;
import com.mofanyi.entity.User;
import com.mofanyi.template.TemplateContext;
import com.mofanyi.template.TemplateRegistry;
import com.mofanyi.template.TemplateBundle;
import com.mofanyi.skill.SkillContext;
import com.mofanyi.skill.SkillDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 第一个 Job Runner：把现有 AiCodeGeneratorFacade 包进持久化执行生命周期。
 * Agent 内部实现不重写，后续可替换为 Pipeline Stage。
 */
@Slf4j
@Component
@Conditional(GenerationExecutionCondition.class)
public class GenerationJobRunner {

    private static final long MAX_EXECUTION_MINUTES = 45;

    private final GenerationJobService jobService;
    private final GenerationJobEventService eventService;
    private final AiCodeGeneratorFacade generatorFacade;
    private final ReasoningProfileRegistry reasoningProfiles;
    private final ChatHistoryService chatHistoryService;
    private final GeneratedResultContentFormatter contentFormatter;
    private final WorkspaceManager workspaceManager;
    private final AppRevisionService revisionService;
    private final GenerationJobQueue jobQueue;
    private final GenerationFailureClassifier failureClassifier;
    private final com.mofanyi.service.GenerationDeadLetterService deadLetterService;
    private final GenerationJobMetrics metrics;
    private final TemplateRegistry templateRegistry;
    private final TemplateContext templateContext;
    private final SkillContext skillContext;
    private final ModelExecutionTrace modelExecutionTrace;
    private final GenerationRuntimeSnapshotValidator runtimeSnapshotValidator;
    private final StackProfileContext stackProfileContext;
    private final AgentOrchestrator agentOrchestrator;
    private final AppRuntimeSchemaService runtimeSchemaService;
    private final AppService appService;
    private final ScheduledExecutorService heartbeatExecutor;

    @Value("${codegen.jobs.worker-id:${HOSTNAME:local-worker}}")
    private String workerId;

    @Value("${codegen.generation-worker.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${codegen.generation-worker.cancel-poll-millis:1000}")
    private long cancelPollMillis;

    public GenerationJobRunner(GenerationJobService jobService,
                               GenerationJobEventService eventService,
                               AiCodeGeneratorFacade generatorFacade,
                               ReasoningProfileRegistry reasoningProfiles,
                               ChatHistoryService chatHistoryService,
                               GeneratedResultContentFormatter contentFormatter,
                               WorkspaceManager workspaceManager,
                               AppRevisionService revisionService,
                               GenerationJobQueue jobQueue,
                               GenerationFailureClassifier failureClassifier,
                               com.mofanyi.service.GenerationDeadLetterService deadLetterService,
                               GenerationJobMetrics metrics,
                               TemplateRegistry templateRegistry,
                               TemplateContext templateContext,
                               SkillContext skillContext,
                               ModelExecutionTrace modelExecutionTrace,
                               GenerationRuntimeSnapshotValidator runtimeSnapshotValidator,
                               StackProfileContext stackProfileContext,
                               AgentOrchestrator agentOrchestrator,
                               AppRuntimeSchemaService runtimeSchemaService,
                               AppService appService,
                               @Qualifier("jobHeartbeatExecutor")
                               ScheduledExecutorService heartbeatExecutor) {
        this.jobService = jobService;
        this.eventService = eventService;
        this.generatorFacade = generatorFacade;
        this.reasoningProfiles = reasoningProfiles;
        this.chatHistoryService = chatHistoryService;
        this.contentFormatter = contentFormatter;
        this.workspaceManager = workspaceManager;
        this.revisionService = revisionService;
        this.jobQueue = jobQueue;
        this.failureClassifier = failureClassifier;
        this.deadLetterService = deadLetterService;
        this.metrics = metrics;
        this.templateRegistry = templateRegistry;
        this.templateContext = templateContext;
        this.skillContext = skillContext;
        this.modelExecutionTrace = modelExecutionTrace;
        this.runtimeSnapshotValidator = runtimeSnapshotValidator;
        this.stackProfileContext = stackProfileContext;
        this.agentOrchestrator = agentOrchestrator;
        this.runtimeSchemaService = runtimeSchemaService;
        this.appService = appService;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    public void run(Long jobId) {
        GenerationJob job = jobService.claim(jobId, workerId, leaseSeconds);
        if (job == null) {
            return;
        }
        WorkspaceManager.WorkspaceHandle workspace = null;
        TemplateBundle template = null;
        StackProfile stackProfile = null;
        java.util.List<SkillDefinition> skills = java.util.List.of();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean callbackFinished = new AtomicBoolean(false);
        AtomicBoolean cancellationSignalled = new AtomicBoolean(false);
        AtomicBoolean pauseSignalled = new AtomicBoolean(false);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<AiCodeGenResultVO> callbackResult = new AtomicReference<>();
        long leaseRenewMillis = Math.max(1_000L, leaseSeconds * 1_000L / 3);
        long monitorMillis = Math.max(250L, cancelPollMillis);
        AtomicLong lastLeaseRenew = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> monitorJob(job, cancellationSignalled, pauseSignalled,
                        lastLeaseRenew, leaseRenewMillis),
                monitorMillis, Math.min(monitorMillis, leaseRenewMillis), TimeUnit.MILLISECONDS);

        try {
            stackProfile = runtimeSnapshotValidator.validateProfile(job);
            stackProfileContext.activate(job.getAppId(), stackProfile);
            GenerationJobCreateRequest request = jobService.readCreateRequest(job);
            CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(job.getCodeGenType());
            ReasoningLevel reasoningLevel = reasoningProfiles.parseLevel(request.getReasoningLevel());
            if ("deploy".equals(request.getRequestType())) {
                runDeployJob(job);
                return;
            }
            if ("publish-revision".equals(request.getRequestType())) {
                runPublishRevisionJob(job, request);
                return;
            }
            if (!agentOrchestrator.prepareBeforeWorkspace(job, type, request)) {
                return;
            }
            workspace = workspaceManager.begin(job);
            template = activateTemplate(job);
            skills = activateSkills(job);
            ThrowUtils.throwIf(jobService.isCancelRequested(job.getId()),
                    ErrorCode.OPERATION_ERROR, "用户已取消生成");
            emitStage(job, "GENERATE", "RUNNING");
            agentOrchestrator.startCoder(job);
            generatorFacade.streamGenerate(type, request.getMessage(), job.getAppId(),
                    new com.mofanyi.core.StreamCallback(
                            progress -> appendQuietly(job, "progress", "GENERATE", progress),
                            reasoning -> appendQuietly(job, "reasoning", "GENERATE", reasoning),
                            result -> {
                                callbackResult.set(result);
                                callbackFinished.set(true);
                                finished.countDown();
                            },
                            error -> {
                                callbackFailure.set(error);
                                callbackFinished.set(true);
                                finished.countDown();
                            }),
                    reasoningLevel, job.getId());

            if (!finished.await(MAX_EXECUTION_MINUTES, TimeUnit.MINUTES)) {
                generatorFacade.stopGenerate(job.getAppId());
                handleFailure(job, new IllegalStateException("timeout: 生成任务执行超时"));
                return;
            }
            if (callbackFailure.get() != null) {
                if (jobService.isPauseRequested(job.getId())) {
                    agentOrchestrator.markPaused(job, "用户请求暂停，当前步骤将在继续时重跑");
                    jobService.pauseFromWorker(job, "用户请求暂停");
                    return;
                }
                handleFailure(job, callbackFailure.get());
                return;
            }
            if (jobService.isPauseRequested(job.getId())) {
                agentOrchestrator.markPaused(job, "用户请求暂停，当前步骤将在继续时重跑");
                jobService.pauseFromWorker(job, "用户请求暂停");
                return;
            }
            agentOrchestrator.completeCoder(job, "源码生成完成");
            complete(job, callbackResult.get(), workspace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            generatorFacade.stopGenerate(job.getAppId());
            handleFailure(job, e);
        } catch (RuntimeException e) {
            if (jobService.isPauseRequested(job.getId())) {
                agentOrchestrator.markPaused(job, "用户请求暂停，当前步骤将在继续时重跑");
                jobService.pauseFromWorker(job, "用户请求暂停");
                return;
            }
            handleFailure(job, e);
        } finally {
            heartbeat.cancel(false);
            workspaceManager.close(workspace);
            stackProfileContext.deactivate(job.getAppId(), stackProfile);
            templateContext.deactivate(job.getAppId(), template);
            skillContext.deactivate(job.getAppId(), skills);
            if (!callbackFinished.get()) {
                log.warn("[job] runner 退出但 Agent 未回调完成，jobId={}", jobId);
            }
        }
    }

    private TemplateBundle activateTemplate(GenerationJob job) {
        if (StrUtil.isBlank(job.getTemplateBundleId())) {
            return null;
        }
        TemplateBundle bundle = templateRegistry.get(job.getTemplateBundleId());
        ThrowUtils.throwIf(bundle == null, ErrorCode.PARAMS_ERROR, "模板不存在");
        ThrowUtils.throwIf(!bundle.version().equals(job.getTemplateVersion())
                        || !bundle.effectiveHash().equals(job.getTemplateHash()),
                ErrorCode.OPERATION_ERROR, "模板版本已变化，拒绝执行旧 Job");
        templateContext.activate(job.getAppId(), bundle);
        return bundle;
    }

    private void runDeployJob(GenerationJob job) {
        emitStage(job, "DEPLOY", "RUNNING");
        User user = new User();
        user.setId(job.getRequestedBy());
        String url = appService.deployApp(job.getAppId(), user);
        appendQuietly(job, "deployed", "DEPLOY",
                Map.of("url", url, "revisionId", String.valueOf(job.getBaseRevisionId())));
        emitStage(job, "COMPLETED", "SUCCEEDED");
        jobService.finish(job, GenerationJobStatusEnum.SUCCEEDED.getValue(),
                "COMPLETED", null, null);
    }

    private void runPublishRevisionJob(GenerationJob job,
                                       GenerationJobCreateRequest request) {
        emitStage(job, "PUBLISH", "RUNNING");
        User user = new User();
        user.setId(job.getRequestedBy());
        revisionService.publishExisting(job.getAppId(), request.getRevisionId(),
                job.getRequestedBy());
        emitStage(job, "DEPLOY", "RUNNING");
        String url = appService.deployApp(job.getAppId(), user);
        appendQuietly(job, "deployed", "DEPLOY",
                Map.of("url", url, "revisionId", String.valueOf(request.getRevisionId())));
        emitStage(job, "COMPLETED", "SUCCEEDED");
        jobService.finish(job, GenerationJobStatusEnum.SUCCEEDED.getValue(),
                "COMPLETED", null, null);
    }

    private java.util.List<SkillDefinition> activateSkills(GenerationJob job) {
        java.util.List<SkillDefinition> skills =
                runtimeSnapshotValidator.validateAndLoadSkills(job);
        if (skills.isEmpty()) {
            return java.util.List.of();
        }
        skillContext.activate(job.getAppId(), skills);
        return skills;
    }

    private void complete(GenerationJob job, AiCodeGenResultVO result,
                          WorkspaceManager.WorkspaceHandle workspace) {
        if (result == null) {
            fail(job, "EMPTY_RESULT", "生成结果为空");
            return;
        }
        GenerationJob latest = jobService.requireJob(job.getId());
        if (GenerationJobStatusEnum.CANCEL_REQUESTED.getValue().equals(latest.getStatus())) {
            handleFailure(job, new GenerationCancelledException(job.getAppId()));
            return;
        }
        String validationStatus = awaitRepairAndValidationStatus(job);
        agentOrchestrator.completeReviewer(job,
                "{\"validationStatus\":\"" + validationStatus + "\"}");
        latest = jobService.requireJob(job.getId());
        if (GenerationJobStatusEnum.CANCEL_REQUESTED.getValue().equals(latest.getStatus())) {
            handleFailure(job, new GenerationCancelledException(job.getAppId()));
            return;
        }
        var revision = revisionService.publishJob(job, workspace, validationStatus);
        runtimeSchemaService.registerFromConfirmedPlan(job, revision.getId());
        agentOrchestrator.completeDeploy(job,
                "{\"revisionId\":\"" + revision.getId()
                        + "\",\"revisionNo\":" + revision.getRevisionNo() + "}");
        appendQuietly(job, "artifact_created", "PUBLISH",
                Map.of("revisionId", String.valueOf(revision.getId()),
                        "revisionNo", revision.getRevisionNo(),
                        "validationStatus", validationStatus));
        emitStage(job, "COMPLETED", "SUCCEEDED");
        appendQuietly(job, "completed", "COMPLETED", result);
        saveAssistantMessageQuietly(job, result, null);
        if (!jobService.finish(job, GenerationJobStatusEnum.SUCCEEDED.getValue(),
                "COMPLETED", null, null)) {
            log.warn("[job] 完成状态写入被 fencing 拒绝，jobId={}", job.getId());
        }
    }

    private String awaitRepairAndValidationStatus(GenerationJob job) {
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        String lastStatus = null;
        while (System.currentTimeMillis() < deadline) {
            String status = generatorFacade.renderFixStatus(job.getAppId());
            if (status != null && !status.equals(lastStatus)) {
                appendQuietly(job, "render_fix", "VERIFY",
                        Map.of("status", status));
                lastStatus = status;
            }
            if (!"fixing".equals(status)) {
                return status == null ? "NOT_APPLICABLE" : status;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待渲染修复被中断", e);
            }
        }
        generatorFacade.stopGenerate(job.getAppId());
        throw new IllegalStateException("渲染修复超时，版本未发布");
    }

    private void handleFailure(GenerationJob job, Throwable error) {
        GenerationJob latest = jobService.requireJob(job.getId());
        boolean cancelled = error instanceof GenerationCancelledException
                || GenerationJobStatusEnum.CANCEL_REQUESTED.getValue().equals(latest.getStatus());
        if (cancelled) {
            emitStage(job, "CANCELLED", "CANCELLED");
            appendQuietly(job, "cancelled", "CANCELLED", Map.of("message", "用户取消生成"));
            saveAssistantMessageQuietly(job, null, "用户取消生成");
            jobService.finish(job, GenerationJobStatusEnum.CANCELLED.getValue(),
                    "CANCELLED", "CANCELLED", "用户取消生成");
            modelExecutionTrace.clear(job.getId());
            return;
        }
        String message = StrUtil.blankToDefault(error.getMessage(), "生成失败");
        int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
        int maxAttempts = job.getMaxAttempts() == null ? 1 : job.getMaxAttempts();
        if (failureClassifier.retryable(error) && attempt < maxAttempts) {
            int delay = retryDelaySeconds(attempt);
            appendQuietly(job, "retry_scheduled", "WAITING_RETRY",
                    Map.of("attempt", attempt, "nextAttempt", attempt + 1,
                            "delaySeconds", delay, "message", message));
            jobService.scheduleRetry(job, error.getClass().getSimpleName(), message);
            jobQueue.schedule(job.getId(), delay);
            metrics.retried();
            return;
        }
        fail(job, error.getClass().getSimpleName(), message);
        saveAssistantMessageQuietly(job, null, message);
    }

    private void fail(GenerationJob job, String errorType, String errorMessage) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("errorType", errorType);
        payload.put("message", errorMessage);
        emitStage(job, "FAILED", "FAILED");
        appendQuietly(job, "failed", "FAILED", payload);
        deadLetterService.record(job, errorType, errorMessage);
        metrics.failed();
        jobService.finish(job, GenerationJobStatusEnum.FAILED.getValue(),
                "FAILED", errorType, errorMessage);
        modelExecutionTrace.clear(job.getId());
    }

    private void monitorJob(GenerationJob job, AtomicBoolean cancellationSignalled,
                            AtomicBoolean pauseSignalled,
                            AtomicLong lastLeaseRenew, long leaseRenewMillis) {
        try {
            if (!cancellationSignalled.get() && jobService.isCancelRequested(job.getId())) {
                cancellationSignalled.set(true);
                generatorFacade.stopGenerate(job.getAppId());
                log.info("[job] Worker 收到跨进程取消请求，jobId={}, appId={}",
                        job.getId(), job.getAppId());
            }
        } catch (RuntimeException e) {
            log.debug("[job] 取消状态轮询失败，jobId={}", job.getId(), e);
        }
        try {
            if (!pauseSignalled.get() && jobService.isPauseRequested(job.getId())) {
                pauseSignalled.set(true);
                generatorFacade.stopGenerate(job.getAppId());
                log.info("[job] Worker 收到暂停请求，jobId={}, appId={}",
                        job.getId(), job.getAppId());
            }
        } catch (RuntimeException e) {
            log.debug("[job] 暂停状态轮询失败，jobId={}", job.getId(), e);
        }
        long now = System.currentTimeMillis();
        long previous = lastLeaseRenew.get();
        if (now - previous >= leaseRenewMillis && lastLeaseRenew.compareAndSet(previous, now)) {
            renewQuietly(job);
        }
    }

    private void renewQuietly(GenerationJob job) {
        try {
            if (!jobService.renewLease(job, workerId, leaseSeconds)) {
                log.warn("[job] 租约续期失败，旧 Worker 后续写入将受 fencing 保护，jobId={}", job.getId());
            }
        } catch (RuntimeException e) {
            log.warn("[job] 租约续期异常，jobId={}", job.getId(), e);
        }
    }

    private void emitStage(GenerationJob job, String stage, String status) {
        appendQuietly(job, "stage", stage, Map.of("stage", stage, "status", status));
    }

    private void appendQuietly(GenerationJob job, String type, String stage, Object payload) {
        try {
            eventService.appendFenced(job, type, stage, payload);
        } catch (RuntimeException e) {
            log.warn("[job] 事件写入失败，jobId={}, type={}", job.getId(), type, e);
        }
    }

    private int retryDelaySeconds(int attempt) {
        return Math.min(60, 5 * Math.max(1, (int) Math.pow(2, Math.max(0, attempt - 1))));
    }

    private void saveAssistantMessageQuietly(GenerationJob job, AiCodeGenResultVO result, String error) {
        try {
            String content = error == null ? contentFormatter.format(result) : error;
            if (StrUtil.isBlank(content)) {
                content = "生成已完成";
            }
            if (content.length() > 500 && error != null) {
                content = content.substring(0, 500);
            }
            chatHistoryService.saveMessage(job.getAppId(), job.getRequestedBy(), content,
                    error == null ? ChatMessageTypeEnum.ASSISTANT.getValue()
                            : ChatMessageTypeEnum.ERROR.getValue(),
                    job.getUserMessageId());
        } catch (RuntimeException e) {
            log.warn("[job] 保存生成对话历史失败，jobId={}", job.getId(), e);
        }
    }
}
