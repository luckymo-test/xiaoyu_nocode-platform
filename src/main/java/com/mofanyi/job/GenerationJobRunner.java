package com.mofanyi.job;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationCancelledException;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.GeneratedResultContentFormatter;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.enums.GenerationJobStatusEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.GenerationJobEventService;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.AppRevisionService;
import com.mofanyi.workspace.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 第一个 Job Runner：把现有 AiCodeGeneratorFacade 包进持久化执行生命周期。
 * Agent 内部实现不重写，后续可替换为 Pipeline Stage。
 */
@Slf4j
@Component
public class GenerationJobRunner {

    private static final int LEASE_SECONDS = 120;
    private static final long MAX_EXECUTION_MINUTES = 45;

    private final GenerationJobService jobService;
    private final GenerationJobEventService eventService;
    private final AiCodeGeneratorFacade generatorFacade;
    private final ReasoningProfileRegistry reasoningProfiles;
    private final ChatHistoryService chatHistoryService;
    private final GeneratedResultContentFormatter contentFormatter;
    private final WorkspaceManager workspaceManager;
    private final AppRevisionService revisionService;
    private final ScheduledExecutorService heartbeatExecutor;

    @Value("${codegen.jobs.worker-id:${HOSTNAME:local-worker}}")
    private String workerId;

    public GenerationJobRunner(GenerationJobService jobService,
                               GenerationJobEventService eventService,
                               AiCodeGeneratorFacade generatorFacade,
                               ReasoningProfileRegistry reasoningProfiles,
                               ChatHistoryService chatHistoryService,
                               GeneratedResultContentFormatter contentFormatter,
                               WorkspaceManager workspaceManager,
                               AppRevisionService revisionService,
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
        this.heartbeatExecutor = heartbeatExecutor;
    }

    public void run(Long jobId) {
        GenerationJob job = jobService.claim(jobId, workerId, LEASE_SECONDS);
        if (job == null) {
            return;
        }
        WorkspaceManager.WorkspaceHandle workspace = null;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean callbackFinished = new AtomicBoolean(false);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicReference<AiCodeGenResultVO> callbackResult = new AtomicReference<>();
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> renewQuietly(job),
                LEASE_SECONDS / 3L, LEASE_SECONDS / 3L, TimeUnit.SECONDS);

        try {
            workspace = workspaceManager.begin(job);
            GenerationJobCreateRequest request = jobService.readCreateRequest(job);
            CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(job.getCodeGenType());
            ReasoningLevel reasoningLevel = reasoningProfiles.parseLevel(request.getReasoningLevel());
            emitStage(job, "GENERATE", "RUNNING");
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
                    reasoningLevel);

            if (!finished.await(MAX_EXECUTION_MINUTES, TimeUnit.MINUTES)) {
                generatorFacade.stopGenerate(job.getAppId());
                fail(job, "TIMEOUT", "生成任务执行超时");
                return;
            }
            if (callbackFailure.get() != null) {
                handleFailure(job, callbackFailure.get());
                return;
            }
            complete(job, callbackResult.get(), workspace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            generatorFacade.stopGenerate(job.getAppId());
            handleFailure(job, e);
        } catch (RuntimeException e) {
            handleFailure(job, e);
        } finally {
            heartbeat.cancel(false);
            workspaceManager.close(workspace);
            if (!callbackFinished.get()) {
                log.warn("[job] runner 退出但 Agent 未回调完成，jobId={}", jobId);
            }
        }
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
        String validationStatus = awaitRepairAndValidationStatus(job.getAppId());
        latest = jobService.requireJob(job.getId());
        if (GenerationJobStatusEnum.CANCEL_REQUESTED.getValue().equals(latest.getStatus())) {
            handleFailure(job, new GenerationCancelledException(job.getAppId()));
            return;
        }
        var revision = revisionService.publishJob(job, workspace, validationStatus);
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

    private String awaitRepairAndValidationStatus(Long appId) {
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = generatorFacade.renderFixStatus(appId);
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
        generatorFacade.stopGenerate(appId);
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
            return;
        }
        String message = StrUtil.blankToDefault(error.getMessage(), "生成失败");
        fail(job, error.getClass().getSimpleName(), message);
        saveAssistantMessageQuietly(job, null, message);
    }

    private void fail(GenerationJob job, String errorType, String errorMessage) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("errorType", errorType);
        payload.put("message", errorMessage);
        emitStage(job, "FAILED", "FAILED");
        appendQuietly(job, "failed", "FAILED", payload);
        jobService.finish(job, GenerationJobStatusEnum.FAILED.getValue(),
                "FAILED", errorType, errorMessage);
    }

    private void renewQuietly(GenerationJob job) {
        try {
            if (!jobService.renewLease(job, workerId, LEASE_SECONDS)) {
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
            eventService.append(job.getId(), type, stage, payload);
        } catch (RuntimeException e) {
            log.warn("[job] 事件写入失败，jobId={}, type={}", job.getId(), type, e);
        }
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
