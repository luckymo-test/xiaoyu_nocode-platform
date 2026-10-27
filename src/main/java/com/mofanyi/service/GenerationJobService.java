package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.entity.App;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.GenerationOutbox;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.mapper.GenerationOutboxMapper;
import com.mofanyi.mapper.GenerationDeadLetterMapper;
import com.mofanyi.job.GenerationJobQueue;
import com.mofanyi.runtime.StackProfileRegistry;
import com.mofanyi.runtime.StackProfile;
import com.mofanyi.template.TemplateRegistry;
import com.mofanyi.template.TemplateBundle;
import com.mofanyi.skill.SkillRegistry;
import com.mofanyi.skill.SkillDefinition;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.enums.AppVisibilityEnum;
import com.mofanyi.model.enums.AppGenerateStatusEnum;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.enums.GenerationJobStatusEnum;
import com.mofanyi.model.vo.GenerationJobVO;
import com.mofanyi.entity.AgentPlan;
import com.mofanyi.entity.AgentStep;
import com.mofanyi.entity.AgentToolCall;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.workflow.WorkflowPlanService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 持久化生成任务控制面。数据库是任务真相源，SSE 只读取事件。
 */
@Slf4j
@Service
public class GenerationJobService {

    public static final String REQUEST_GENERATE = "generate";
    public static final String REQUEST_DEPLOY = "deploy";
    public static final String REQUEST_PUBLISH_REVISION = "publish-revision";
    public static final String STAGE_QUEUED = "QUEUED";

    private final GenerationJobMapper jobMapper;
    private final GenerationOutboxMapper outboxMapper;
    private final GenerationJobEventService eventService;
    private final AppAuthService appAuthService;
    private final WorkspaceService workspaceService;
    private final ChatHistoryService chatHistoryService;
    private final AppService appService;
    private final ReasoningProfileRegistry reasoningProfiles;
    private final ObjectMapper objectMapper;
    private final GenerationJobQueue jobQueue;
    private final GenerationDeadLetterMapper deadLetterMapper;
    private final StackProfileRegistry stackProfiles;
    private final TemplateRegistry templateRegistry;
    private final SkillRegistry skillRegistry;
    private final WorkflowPlanService workflowPlanService;

    @Value("${codegen.jobs.max-attempts:3}")
    private int maxAttempts;

    @Value("${codegen.jobs.quota.user-active-jobs:2}")
    private int userActiveJobLimit;

    @Value("${codegen.jobs.quota.max-queued-jobs:1000}")
    private int maxQueuedJobs;

    public GenerationJobService(GenerationJobMapper jobMapper,
                                GenerationOutboxMapper outboxMapper,
                                GenerationJobEventService eventService,
                                AppAuthService appAuthService,
                                WorkspaceService workspaceService,
                                ChatHistoryService chatHistoryService,
                                AppService appService,
                                ReasoningProfileRegistry reasoningProfiles,
                                GenerationJobQueue jobQueue,
                                GenerationDeadLetterMapper deadLetterMapper,
                                StackProfileRegistry stackProfiles,
                                TemplateRegistry templateRegistry,
                                SkillRegistry skillRegistry,
                                WorkflowPlanService workflowPlanService,
                                ObjectMapper objectMapper) {
        this.jobMapper = jobMapper;
        this.outboxMapper = outboxMapper;
        this.eventService = eventService;
        this.appAuthService = appAuthService;
        this.workspaceService = workspaceService;
        this.chatHistoryService = chatHistoryService;
        this.appService = appService;
        this.reasoningProfiles = reasoningProfiles;
        this.jobQueue = jobQueue;
        this.deadLetterMapper = deadLetterMapper;
        this.stackProfiles = stackProfiles;
        this.templateRegistry = templateRegistry;
        this.skillRegistry = skillRegistry;
        this.workflowPlanService = workflowPlanService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 Job。相同 appId + Idempotency-Key + 请求摘要返回原 Job。
     */
    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO create(Long appId, GenerationJobCreateRequest request,
                                  String idempotencyKey, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(request.getMessage()), ErrorCode.PARAMS_ERROR, "用户需求不能为空");
        ThrowUtils.throwIf(request.getMessage().length() > 50_000,
                ErrorCode.PARAMS_ERROR, "用户需求过长");
        ThrowUtils.throwIf(StrUtil.isBlank(idempotencyKey), ErrorCode.PARAMS_ERROR, "Idempotency-Key 不能为空");
        ThrowUtils.throwIf(idempotencyKey.length() > 128, ErrorCode.PARAMS_ERROR, "Idempotency-Key 过长");

        App app = appAuthService.requireCanGenerate(appId, loginUser);
        Long workspaceId = app.getWorkspaceId();
        if (workspaceId == null) {
            workspaceId = workspaceService.ensureDefaultWorkspace(loginUser).getId();
        }
        workspaceService.requireMember(workspaceId, loginUser.getId());

        normalizeRequest(request, app);
        String requestHash = hashRequest(request);
        GenerationJob existing = jobMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("idempotencyKey", idempotencyKey)
                .limit(1));
        if (existing != null) {
            ThrowUtils.throwIf(!requestHash.equals(existing.getRequestHash()),
                    ErrorCode.PARAMS_ERROR, "同一幂等键不能用于不同请求");
            return toVO(existing);
        }

        long userActiveCount = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("requestedBy", loginUser.getId())
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.RUNNING.getValue(),
                        GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue(),
                        GenerationJobStatusEnum.PAUSE_REQUESTED.getValue(),
                        GenerationJobStatusEnum.PAUSED.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue(),
                        GenerationJobStatusEnum.CANCEL_REQUESTED.getValue()));
        ThrowUtils.throwIf(userActiveCount >= Math.max(1, userActiveJobLimit),
                ErrorCode.OPERATION_ERROR, "当前用户的生成任务已达并发上限");
        long queuedCount = jobMapper.selectCountByQuery(QueryWrapper.create()
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue()));
        ThrowUtils.throwIf(queuedCount >= Math.max(1, maxQueuedJobs),
                ErrorCode.OPERATION_ERROR, "生成队列已满，请稍后重试");

        long activeCount = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.RUNNING.getValue(),
                        GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue(),
                        GenerationJobStatusEnum.PAUSE_REQUESTED.getValue(),
                        GenerationJobStatusEnum.PAUSED.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue(),
                        GenerationJobStatusEnum.CANCEL_REQUESTED.getValue()));
        ThrowUtils.throwIf(activeCount > 0, ErrorCode.OPERATION_ERROR,
                "该应用已有生成任务，请等待当前任务结束或先取消");

        ChatHistory userMessage = chatHistoryService.saveMessage(appId, loginUser.getId(),
                request.getMessage(), ChatMessageTypeEnum.USER.getValue(), null);
        StackProfile stackProfile = stackProfiles.require(request.getStackProfileId());
        LocalDateTime now = LocalDateTime.now();
        GenerationJob job = GenerationJob.builder()
                .workspaceId(workspaceId)
                .appId(appId)
                .requestedBy(loginUser.getId())
                .requestType(REQUEST_GENERATE)
                .workflowVersion(normalizeWorkflowVersion(request.getWorkflowVersion()))
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .requestPayload(writeJson(request))
                .userMessageId(userMessage.getId())
                .status(GenerationJobStatusEnum.QUEUED.getValue())
                .currentStage(STAGE_QUEUED)
                .codeGenType(request.getCodeGenType())
                .stackProfileId(request.getStackProfileId())
                .stackProfileVersion(stackProfile.version())
                .templateBundleId(request.getTemplateBundleId())
                .templateVersion(request.getTemplateVersion())
                .templateHash(request.getTemplateHash())
                .skillsJson(writeJson(request.getSkills()))
                .skillsSnapshotJson(writeSkillSnapshot(request.getSkills()))
                .visibility(request.getVisibility())
                .baseRevisionId(app.getCurrentRevision())
                .priority(0)
                .attempt(0)
                .maxAttempts(Math.max(1, maxAttempts))
                .fencingToken(0L)
                .nextEventSeq(0L)
                .createTime(now)
                .updateTime(now)
                .build();
        try {
        ThrowUtils.throwIf(jobMapper.insertSelective(job) == 0 || job.getId() == null,
                    ErrorCode.OPERATION_ERROR, "创建生成任务失败");
        } catch (DuplicateKeyException e) {
            GenerationJob raced = jobMapper.selectOneByQuery(QueryWrapper.create()
                    .eq("appId", appId)
                    .eq("idempotencyKey", idempotencyKey)
                    .limit(1));
            if (raced != null && requestHash.equals(raced.getRequestHash())) {
                return toVO(raced);
            }
            throw e;
        }

        GenerationOutbox outbox = GenerationOutbox.builder()
                .jobId(job.getId())
                .eventType("DISPATCH")
                .payload(String.valueOf(job.getId()))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createTime(now)
                .updateTime(now)
                .build();
        ThrowUtils.throwIf(outboxMapper.insertSelective(outbox) == 0, ErrorCode.OPERATION_ERROR,
                "创建任务投递记录失败");
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        return toVO(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO createDeploy(Long appId, String idempotencyKey, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        App app = appAuthService.requireCanPublish(appId, loginUser);
        ThrowUtils.throwIf(app.getCurrentRevision() == null,
                ErrorCode.OPERATION_ERROR, "应用还没有可发布版本");
        ThrowUtils.throwIf(StrUtil.isBlank(idempotencyKey),
                ErrorCode.PARAMS_ERROR, "Idempotency-Key 不能为空");
        long active = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.RUNNING.getValue(),
                        GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue(),
                        GenerationJobStatusEnum.PAUSE_REQUESTED.getValue(),
                        GenerationJobStatusEnum.PAUSED.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue(),
                        GenerationJobStatusEnum.CANCEL_REQUESTED.getValue()));
        ThrowUtils.throwIf(active > 0, ErrorCode.OPERATION_ERROR,
                "该应用已有活跃任务，请等待结束或先取消");
        GenerationJobCreateRequest request = new GenerationJobCreateRequest();
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "未知生成类型");
        StackProfile profile = stackProfiles.resolve("auto", type);
        request.setMessage("发布当前应用版本");
        request.setRequestType(REQUEST_DEPLOY);
        request.setCodeGenType(app.getCodeGenType());
        request.setStackProfileId(profile.id());
        request.setWorkflowVersion(1);
        String payload = writeJson(request);
        String hash = hashRequest(request);
        GenerationJob existing = jobMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("idempotencyKey", idempotencyKey)
                .limit(1));
        if (existing != null) {
            ThrowUtils.throwIf(!hash.equals(existing.getRequestHash()),
                    ErrorCode.PARAMS_ERROR, "同一幂等键不能用于不同请求");
            return toVO(existing);
        }
        Long workspaceId = app.getWorkspaceId() == null
                ? workspaceService.ensureDefaultWorkspace(loginUser).getId()
                : app.getWorkspaceId();
        LocalDateTime now = LocalDateTime.now();
        GenerationJob job = GenerationJob.builder()
                .workspaceId(workspaceId)
                .appId(appId)
                .requestedBy(loginUser.getId())
                .requestType(REQUEST_DEPLOY)
                .workflowVersion(1)
                .idempotencyKey(idempotencyKey)
                .requestHash(hash)
                .requestPayload(payload)
                .status(GenerationJobStatusEnum.QUEUED.getValue())
                .currentStage("DEPLOY_QUEUED")
                .codeGenType(app.getCodeGenType())
                .stackProfileId(profile.id())
                .stackProfileVersion(profile.version())
                .baseRevisionId(app.getCurrentRevision())
                .visibility(app.getVisibility())
                .priority(0)
                .attempt(0)
                .maxAttempts(Math.max(1, maxAttempts))
                .fencingToken(0L)
                .nextEventSeq(0L)
                .createTime(now)
                .updateTime(now)
                .build();
        jobMapper.insertSelective(job);
        outboxMapper.insertSelective(GenerationOutbox.builder()
                .jobId(job.getId())
                .eventType("DISPATCH")
                .payload(String.valueOf(job.getId()))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createTime(now)
                .updateTime(now)
                .build());
        return toVO(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO createRevisionPublish(Long appId, Long revisionId,
                                                 String idempotencyKey, User loginUser) {
        ThrowUtils.throwIf(revisionId == null || revisionId <= 0, ErrorCode.PARAMS_ERROR);
        App app = appAuthService.requireCanPublish(appId, loginUser);
        long active = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.RUNNING.getValue(),
                        GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue(),
                        GenerationJobStatusEnum.PAUSE_REQUESTED.getValue(),
                        GenerationJobStatusEnum.PAUSED.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue(),
                        GenerationJobStatusEnum.CANCEL_REQUESTED.getValue()));
        ThrowUtils.throwIf(active > 0, ErrorCode.OPERATION_ERROR,
                "该应用已有活跃任务，无法切换版本");
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "未知生成类型");
        StackProfile profile = stackProfiles.resolve("auto", type);
        GenerationJobCreateRequest request = new GenerationJobCreateRequest();
        request.setMessage("发布版本 " + revisionId);
        request.setRequestType(REQUEST_PUBLISH_REVISION);
        request.setCodeGenType(app.getCodeGenType());
        request.setStackProfileId(profile.id());
        request.setRevisionId(revisionId);
        request.setWorkflowVersion(1);
        String key = StrUtil.blankToDefault(idempotencyKey,
                "publish-revision-" + revisionId + "-" + System.currentTimeMillis());
        String payload = writeJson(request);
        String hash = hashRequest(request);
        GenerationJob existing = jobMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId).eq("idempotencyKey", key).limit(1));
        if (existing != null) {
            ThrowUtils.throwIf(!hash.equals(existing.getRequestHash()),
                    ErrorCode.PARAMS_ERROR, "同一幂等键不能用于不同请求");
            return toVO(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        GenerationJob job = GenerationJob.builder()
                .workspaceId(app.getWorkspaceId())
                .appId(appId)
                .requestedBy(loginUser.getId())
                .requestType(REQUEST_PUBLISH_REVISION)
                .workflowVersion(1)
                .idempotencyKey(key)
                .requestHash(hash)
                .requestPayload(payload)
                .status(GenerationJobStatusEnum.QUEUED.getValue())
                .currentStage("PUBLISH_QUEUED")
                .codeGenType(app.getCodeGenType())
                .stackProfileId(profile.id())
                .stackProfileVersion(profile.version())
                .baseRevisionId(app.getCurrentRevision())
                .candidateRevisionId(revisionId)
                .visibility(app.getVisibility())
                .priority(0)
                .attempt(0)
                .maxAttempts(Math.max(1, maxAttempts))
                .fencingToken(0L)
                .nextEventSeq(0L)
                .createTime(now)
                .updateTime(now)
                .build();
        jobMapper.insertSelective(job);
        outboxMapper.insertSelective(GenerationOutbox.builder()
                .jobId(job.getId())
                .eventType("DISPATCH")
                .payload(String.valueOf(job.getId()))
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createTime(now)
                .updateTime(now)
                .build());
        return toVO(job);
    }

    public GenerationJob getAuthorized(Long jobId, User loginUser) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        GenerationJob job = requireJob(jobId);
        appAuthService.requireCanGenerate(job.getAppId(), loginUser);
        workspaceService.requireMember(job.getWorkspaceId(), loginUser.getId());
        return job;
    }

    public GenerationJobVO getVO(Long jobId, User loginUser) {
        return toVO(getAuthorized(jobId, loginUser));
    }

    public GenerationJobVO getLatestForApp(Long appId, User loginUser) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR);
        App app = appAuthService.requireCanGenerate(appId, loginUser);
        workspaceService.requireMember(app.getWorkspaceId(), loginUser.getId());
        GenerationJob latest = jobMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("requestType", REQUEST_GENERATE)
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(1));
        return latest == null ? null : toVO(latest);
    }

    public GenerationJob requireJob(Long jobId) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        GenerationJob job = jobMapper.selectOneById(jobId);
        ThrowUtils.throwIf(job == null, ErrorCode.NOT_FOUND_ERROR, "生成任务不存在");
        return job;
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO cancel(Long jobId, User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        if (GenerationJobStatusEnum.isTerminal(job.getStatus())) {
            return toVO(job);
        }
        if (GenerationJobStatusEnum.PAUSED.getValue().equals(job.getStatus())) {
            if (jobMapper.requestCancel(jobId) == 1) {
                eventService.append(jobId, "cancel_requested", "CANCEL_REQUESTED",
                        java.util.Map.of("message", "已请求取消暂停任务"));
            }
            return toVO(requireJob(jobId));
        }
        int queued = jobMapper.cancelQueued(jobId);
        if (queued == 1) {
            eventService.append(jobId, "cancelled", "CANCELLED",
                    java.util.Map.of("message", "用户取消生成"));
            appService.updateGenerateStatus(job.getAppId(), AppGenerateStatusEnum.STOPPED.getValue());
        } else {
            if (jobMapper.requestCancel(jobId) == 1) {
                eventService.append(jobId, "cancel_requested", "CANCEL_REQUESTED",
                        java.util.Map.of("message", "已请求取消，Worker 将在下一个工具边界退出"));
            }
        }
        return toVO(requireJob(jobId));
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO pause(Long jobId, User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        if (GenerationJobStatusEnum.PAUSED.getValue().equals(job.getStatus())) {
            return toVO(job);
        }
        if (GenerationJobStatusEnum.isTerminal(job.getStatus())) {
            ThrowUtils.throwIf(true, ErrorCode.OPERATION_ERROR, "任务已结束，无法暂停");
        }
        boolean workerOwned = GenerationJobStatusEnum.RUNNING.getValue().equals(job.getStatus());
        if (workerOwned) {
            jobMapper.requestPause(jobId);
            eventService.append(jobId, "pause_requested", "PAUSE_REQUESTED",
                    java.util.Map.of("message", "将在当前安全边界暂停"));
        } else {
            jobMapper.markPaused(jobId, null);
            appService.updateGenerateStatus(job.getAppId(),
                    AppGenerateStatusEnum.PAUSED.getValue());
            eventService.append(jobId, "paused", "PAUSED",
                    java.util.Map.of("message", "任务已暂停"));
        }
        return toVO(requireJob(jobId));
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO resume(Long jobId, User loginUser) {
        getAuthorized(jobId, loginUser);
        ThrowUtils.throwIf(jobMapper.resumePaused(jobId) != 1,
                ErrorCode.OPERATION_ERROR, "只有已暂停任务可以继续");
        GenerationJob resumed = requireJob(jobId);
        appService.updateGenerateStatus(resumed.getAppId(),
                AppGenerateStatusEnum.GENERATING.getValue());
        eventService.append(jobId, "resumed", "RESUMED",
                java.util.Map.of("message", "任务从第一个未成功步骤继续"));
        jobQueue.schedule(jobId, 0);
        return toVO(requireJob(jobId));
    }

    public void pauseFromWorker(GenerationJob job, String reason) {
        ThrowUtils.throwIf(job == null || job.getFencingToken() == null,
                ErrorCode.PARAMS_ERROR);
        if (jobMapper.markPaused(job.getId(), job.getFencingToken()) == 1) {
            appService.updateGenerateStatus(job.getAppId(),
                    AppGenerateStatusEnum.PAUSED.getValue());
            eventService.append(job.getId(), "paused", "PAUSED",
                    java.util.Map.of("message", StrUtil.blankToDefault(reason, "任务已暂停")));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO confirmPlan(Long jobId, String planHash, User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        ThrowUtils.throwIf(!GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue()
                        .equals(job.getStatus()),
                ErrorCode.OPERATION_ERROR, "当前任务不在等待确认状态");
        ThrowUtils.throwIf(StrUtil.isBlank(planHash), ErrorCode.PARAMS_ERROR, "计划确认哈希不能为空");
        AgentPlan plan = workflowPlanService.confirm(job, planHash, AiCallContext.ofJob(
                job.getAppId(), loginUser.getId(), "workflow-plan", job.getId()));
        ThrowUtils.throwIf(jobMapper.confirmPlan(jobId, plan.getPlanHash(), loginUser.getId()) != 1,
                ErrorCode.OPERATION_ERROR, "计划确认已失效，请刷新后重试");
        eventService.append(jobId, "plan_confirmed", "CONFIRMED",
                java.util.Map.of("planHash", plan.getPlanHash(), "confirmedBy", loginUser.getId()));
        jobQueue.schedule(jobId, 0);
        return toVO(requireJob(jobId));
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO revisePlan(Long jobId, String planHash, String comment,
                                      User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        ThrowUtils.throwIf(!GenerationJobStatusEnum.WAITING_CONFIRMATION.getValue()
                        .equals(job.getStatus()),
                ErrorCode.OPERATION_ERROR, "当前任务不在等待确认状态");
        GenerationJobCreateRequest request = readCreateRequest(job);
        AgentPlan revised = workflowPlanService.revise(job, planHash, comment, request,
                AiCallContext.ofJob(job.getAppId(), loginUser.getId(),
                        "workflow-revise", jobId));
        ThrowUtils.throwIf(jobMapper.replaceWaitingPlan(jobId, revised.getPlanHash()) != 1,
                ErrorCode.OPERATION_ERROR, "计划修改失败，请重试");
        eventService.append(jobId, "plan_revised", "CONFIRMATION",
                java.util.Map.of("planHash", revised.getPlanHash(),
                        "planVersion", revised.getPlanVersion()));
        return toVO(requireJob(jobId));
    }

    public AgentPlan getLatestPlan(Long jobId, User loginUser) {
        getAuthorized(jobId, loginUser);
        return workflowPlanService.findLatest(jobId);
    }

    public List<AgentStep> listSteps(Long jobId, User loginUser) {
        getAuthorized(jobId, loginUser);
        return workflowPlanService.listSteps(jobId);
    }

    public List<AgentToolCall> listToolCalls(Long jobId, User loginUser) {
        getAuthorized(jobId, loginUser);
        return workflowPlanService.listToolCalls(jobId);
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO retryStep(Long jobId, String stepKey, User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        ThrowUtils.throwIf(!GenerationJobStatusEnum.FAILED.getValue().equals(job.getStatus())
                        && !GenerationJobStatusEnum.PAUSED.getValue().equals(job.getStatus()),
                ErrorCode.OPERATION_ERROR, "任务未处于失败或暂停状态");
        workflowPlanService.resetStep(jobId, stepKey);
        if (GenerationJobStatusEnum.FAILED.getValue().equals(job.getStatus())) {
            ThrowUtils.throwIf(jobMapper.requeueFailed(jobId) != 1,
                    ErrorCode.OPERATION_ERROR, "重试任务失败");
        } else {
            ThrowUtils.throwIf(jobMapper.resumePaused(jobId) != 1,
                    ErrorCode.OPERATION_ERROR, "继续任务失败");
        }
        eventService.append(jobId, "step_retry_requested", "RETRY_REQUESTED",
                java.util.Map.of("stepKey", stepKey));
        appService.updateGenerateStatus(job.getAppId(),
                AppGenerateStatusEnum.GENERATING.getValue());
        jobQueue.schedule(jobId, 0);
        return toVO(requireJob(jobId));
    }

    public GenerationJob claim(Long jobId, String owner, int leaseSeconds) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(owner), ErrorCode.PARAMS_ERROR);
        int updated = jobMapper.claim(jobId, owner, LocalDateTime.now().plusSeconds(leaseSeconds));
        if (updated == 0) {
            GenerationJob current = jobMapper.selectOneById(jobId);
            if (current != null
                    && GenerationJobStatusEnum.CANCEL_REQUESTED.getValue().equals(current.getStatus())) {
                jobMapper.finishCancelRequested(jobId);
            }
            return null;
        }
        return requireJob(jobId);
    }

    public boolean renewLease(GenerationJob job, String owner, int leaseSeconds) {
        if (job == null) {
            return false;
        }
        return jobMapper.renewLease(job.getId(), owner, job.getFencingToken(),
                LocalDateTime.now().plusSeconds(leaseSeconds)) == 1;
    }

    /**
     * Worker 通过数据库状态感知跨进程取消，不依赖 API 进程内任务注册表。
     */
    public boolean isCancelRequested(Long jobId) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        return jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("id", jobId)
                .eq("status", GenerationJobStatusEnum.CANCEL_REQUESTED.getValue())) == 1;
    }

    public boolean isPauseRequested(Long jobId) {
        ThrowUtils.throwIf(jobId == null || jobId <= 0, ErrorCode.PARAMS_ERROR);
        return jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("id", jobId)
                .eq("status", GenerationJobStatusEnum.PAUSE_REQUESTED.getValue())) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finish(GenerationJob job, String status, String stage,
                          String errorType, String errorMessage) {
        ThrowUtils.throwIf(job == null, ErrorCode.PARAMS_ERROR);
        boolean updated = jobMapper.finish(job.getId(), job.getFencingToken(), status, stage,
                truncate(errorType, 64), truncate(errorMessage, 4000)) == 1;
        if (updated) {
            String appStatus = switch (status) {
                case "SUCCEEDED" -> AppGenerateStatusEnum.DONE.getValue();
                case "CANCELLED" -> AppGenerateStatusEnum.STOPPED.getValue();
                default -> AppGenerateStatusEnum.FAILED.getValue();
            };
            appService.updateGenerateStatus(job.getAppId(), appStatus);
        }
        return updated;
    }

    public void recordPublishedRevision(GenerationJob job, Long revisionId) {
        ThrowUtils.throwIf(job == null || revisionId == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(jobMapper.recordPublishedRevision(job.getId(), job.getFencingToken(),
                revisionId, revisionId) != 1,
                ErrorCode.OPERATION_ERROR, "记录发布版本失败");
    }

    public void scheduleRetry(GenerationJob job, String errorType, String errorMessage) {
        ThrowUtils.throwIf(job == null || job.getFencingToken() == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(jobMapper.scheduleRetry(job.getId(), job.getFencingToken(),
                        truncate(errorType, 64), truncate(errorMessage, 4000)) != 1,
                ErrorCode.OPERATION_ERROR, "安排任务重试失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public GenerationJobVO retryFailed(Long jobId, User loginUser) {
        GenerationJob job = getAuthorized(jobId, loginUser);
        ThrowUtils.throwIf(!GenerationJobStatusEnum.FAILED.getValue().equals(job.getStatus()),
                ErrorCode.OPERATION_ERROR, "只有失败任务可以重放");
        long active = jobMapper.selectCountByQuery(QueryWrapper.create()
                .eq("appId", job.getAppId())
                .in("status", GenerationJobStatusEnum.QUEUED.getValue(),
                        GenerationJobStatusEnum.RUNNING.getValue(),
                        GenerationJobStatusEnum.WAITING_RETRY.getValue(),
                        GenerationJobStatusEnum.CANCEL_REQUESTED.getValue()));
        ThrowUtils.throwIf(active > 0, ErrorCode.OPERATION_ERROR,
                "该应用已有活跃任务，无法重放死信");
        ThrowUtils.throwIf(jobMapper.requeueFailed(jobId) != 1,
                ErrorCode.OPERATION_ERROR, "重放任务失败");
        eventService.append(jobId, "retry_requested", "RETRY_REQUESTED",
                java.util.Map.of("message", "用户手动重放失败任务"));
        deadLetterMapper.markReplayed(jobId, loginUser.getId());
        appService.updateGenerateStatus(job.getAppId(), AppGenerateStatusEnum.GENERATING.getValue());
        jobQueue.schedule(jobId, 0);
        return toVO(requireJob(jobId));
    }

    public JsonNode parseRequest(GenerationJob job) {
        ThrowUtils.throwIf(job == null || StrUtil.isBlank(job.getRequestPayload()),
                ErrorCode.PARAMS_ERROR);
        try {
            return objectMapper.readTree(job.getRequestPayload());
        } catch (Exception e) {
            throw new IllegalStateException("生成任务请求快照损坏", e);
        }
    }

    public GenerationJobCreateRequest readCreateRequest(GenerationJob job) {
        ThrowUtils.throwIf(job == null || StrUtil.isBlank(job.getRequestPayload()),
                ErrorCode.PARAMS_ERROR);
        try {
            return objectMapper.readValue(job.getRequestPayload(), GenerationJobCreateRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("生成任务请求快照损坏", e);
        }
    }

    private void normalizeRequest(GenerationJobCreateRequest request, App app) {
        request.setRequestType(StrUtil.blankToDefault(request.getRequestType(), REQUEST_GENERATE));
        ThrowUtils.throwIf(!REQUEST_GENERATE.equals(request.getRequestType()),
                ErrorCode.PARAMS_ERROR, "当前仅支持 generate 任务");
        String type = StrUtil.blankToDefault(request.getCodeGenType(), app.getCodeGenType());
        type = StrUtil.blankToDefault(type, CodeGenTypeEnum.HTML.getValue());
        ThrowUtils.throwIf(CodeGenTypeEnum.getEnumByValue(type) == null,
                ErrorCode.PARAMS_ERROR, "不支持的生成类型");
        request.setCodeGenType(type);
        if (StrUtil.isBlank(request.getProjectTemplateId())) {
            request.setProjectTemplateId(app.getProjectTemplateId());
        }
        StackProfile profile = stackProfiles.resolve(request.getStackProfileId(),
                CodeGenTypeEnum.getEnumByValue(type));
        request.setStackProfileId(profile.id());
        TemplateBundle template = templateRegistry.resolve(request.getTemplateHint(),
                request.getMessage(), profile.id());
        request.setTemplateBundleId(template.id());
        request.setTemplateVersion(template.version());
        request.setTemplateHash(template.effectiveHash());
        List<SkillDefinition> skills = skillRegistry.resolve(request.getSkills(), profile.id());
        request.setSkills(skills.stream().map(SkillDefinition::id).toList());
        request.setReasoningLevel(reasoningProfiles.parseLevel(request.getReasoningLevel()).code());
        String visibility = StrUtil.blankToDefault(request.getVisibility(),
                StrUtil.blankToDefault(app.getVisibility(), AppVisibilityEnum.PRIVATE.getValue()));
        ThrowUtils.throwIf(AppVisibilityEnum.getEnumByValue(visibility) == null,
                ErrorCode.PARAMS_ERROR, "可见范围不合法");
        request.setVisibility(visibility);
        request.setWorkflowVersion(normalizeWorkflowVersion(request.getWorkflowVersion()));
    }

    private int normalizeWorkflowVersion(Integer requested) {
        return requested != null && requested == 1 ? 1 : 2;
    }

    private String hashRequest(GenerationJobCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(writeJson(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("计算请求摘要失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化任务请求失败", e);
        }
    }

    private String truncate(String text, int maxLength) {
        return text == null ? null : StrUtil.maxLength(text, maxLength);
    }

    public GenerationJobVO toVO(GenerationJob job) {
        ThrowUtils.throwIf(job == null, ErrorCode.PARAMS_ERROR);
        GenerationJobVO vo = new GenerationJobVO();
        vo.setJobId(job.getId());
        vo.setWorkspaceId(job.getWorkspaceId());
        vo.setAppId(job.getAppId());
        vo.setRequestedBy(job.getRequestedBy());
        vo.setRequestType(job.getRequestType());
        vo.setWorkflowVersion(job.getWorkflowVersion());
        vo.setConfirmedPlanHash(job.getConfirmedPlanHash());
        vo.setConfirmedAt(job.getConfirmedAt());
        vo.setConfirmedBy(job.getConfirmedBy());
        vo.setStatus(job.getStatus());
        vo.setCurrentStage(job.getCurrentStage());
        vo.setCodeGenType(job.getCodeGenType());
        vo.setStackProfileId(job.getStackProfileId());
        vo.setStackProfileVersion(job.getStackProfileVersion());
        vo.setTemplateBundleId(job.getTemplateBundleId());
        vo.setTemplateVersion(job.getTemplateVersion());
        vo.setTemplateHash(job.getTemplateHash());
        vo.setSkillIds(readSkillIds(job.getSkillsJson()));
        vo.setVisibility(job.getVisibility());
        vo.setAttempt(job.getAttempt());
        vo.setMaxAttempts(job.getMaxAttempts());
        vo.setErrorType(job.getErrorType());
        vo.setErrorMessage(job.getErrorMessage());
        vo.setCreateTime(job.getCreateTime());
        vo.setUpdateTime(job.getUpdateTime());
        vo.setEventsUrl("/v1/generation-jobs/" + job.getId() + "/events");
        return vo;
    }

    public List<String> readSkillIds(String skillsJson) {
        if (StrUtil.isBlank(skillsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(skillsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从 V15 快照读取 Skill 版本；旧 Job 没有快照时返回空 Map，由 Worker 兼容执行。
     */
    public java.util.Map<String, String> readSkillVersions(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            return java.util.Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String id = node.path("id").asText("");
                    String version = node.path("version").asText("");
                    if (StrUtil.isNotBlank(id)) {
                        versions.put(id, version);
                    }
                }
            }
            return java.util.Map.copyOf(versions);
        } catch (Exception e) {
            throw new IllegalStateException("生成任务 Skill 快照损坏", e);
        }
    }

    private String writeSkillSnapshot(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return "[]";
        }
        List<java.util.Map<String, String>> snapshot = skillIds.stream()
                .map(id -> {
                    SkillDefinition skill = skillRegistry.get(id);
                    if (skill == null) {
                        return java.util.Map.of("id", id, "version", "");
                    }
                    return java.util.Map.of("id", id,
                            "version", StrUtil.blankToDefault(skill.version(), ""));
                })
                .toList();
        return writeJson(snapshot);
    }
}
