package com.mofanyi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.entity.App;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.User;
import com.mofanyi.mapper.GenerationJobMapper;
import com.mofanyi.mapper.GenerationOutboxMapper;
import com.mofanyi.mapper.GenerationDeadLetterMapper;
import com.mofanyi.job.GenerationJobQueue;
import com.mofanyi.runtime.StackProfileRegistry;
import com.mofanyi.template.TemplateRegistry;
import com.mofanyi.template.TemplateBundle;
import com.mofanyi.skill.SkillRegistry;
import com.mofanyi.workflow.WorkflowPlanService;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.vo.GenerationJobVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationJobServiceTest {

    private static final long APP_ID = 1001L;
    private static final long USER_ID = 2001L;
    private static final long WORKSPACE_ID = 3001L;

    private GenerationJobMapper jobMapper;
    private GenerationOutboxMapper outboxMapper;
    private GenerationJobEventService eventService;
    private ChatHistoryService chatHistoryService;
    private GenerationJobService service;
    private User user;
    private App app;
    private TemplateRegistry templateRegistry;
    private AppAuthService appAuthService;
    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        jobMapper = mock(GenerationJobMapper.class);
        outboxMapper = mock(GenerationOutboxMapper.class);
        eventService = mock(GenerationJobEventService.class);
        appAuthService = mock(AppAuthService.class);
        workspaceService = mock(WorkspaceService.class);
        chatHistoryService = mock(ChatHistoryService.class);
        templateRegistry = new TemplateRegistry();
        service = new GenerationJobService(
                jobMapper,
                outboxMapper,
                eventService,
                appAuthService,
                workspaceService,
                chatHistoryService,
                mock(AppService.class),
                new ReasoningProfileRegistry(),
                mock(GenerationJobQueue.class),
                mock(GenerationDeadLetterMapper.class),
                new StackProfileRegistry(false, false),
                templateRegistry,
                new SkillRegistry(),
                mock(WorkflowPlanService.class),
                new ObjectMapper());

        user = new User();
        user.setId(USER_ID);
        app = new App();
        app.setId(APP_ID);
        app.setUserId(USER_ID);
        app.setWorkspaceId(WORKSPACE_ID);
        app.setCodeGenType("html");
        app.setVisibility("private");
        when(appAuthService.requireCanGenerate(APP_ID, user)).thenReturn(app);
    }

    @Test
    @DisplayName("相同 Idempotency-Key + 相同请求返回已有 Job，不重复写消息/Outbox")
    void create_isIdempotentForSameRequest() {
        GenerationJobCreateRequest request = request();
        String requestHash = hashOf(request);
        GenerationJob existing = GenerationJob.builder()
                .id(9001L)
                .workspaceId(WORKSPACE_ID)
                .appId(APP_ID)
                .requestedBy(USER_ID)
                .requestType("generate")
                .idempotencyKey("click-1")
                .requestHash(requestHash)
                .status("QUEUED")
                .currentStage("QUEUED")
                .codeGenType("html")
                .stackProfileId("html-static")
                .visibility("private")
                .attempt(0)
                .maxAttempts(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        when(jobMapper.selectOneByQuery(any())).thenReturn(existing);

        GenerationJobVO result = service.create(APP_ID, request, "click-1", user);

        assertEquals(9001L, result.getJobId());
        verify(chatHistoryService, never()).saveMessage(anyLong(), anyLong(),
                anyString(), anyString(), any());
        verify(outboxMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("同 App 已有活跃 Job 时拒绝创建第二个任务")
    void create_rejectsSecondActiveJob() {
        when(jobMapper.selectOneByQuery(any())).thenReturn(null);
        when(jobMapper.selectCountByQuery(any())).thenReturn(1L);

        assertThrows(RuntimeException.class,
                () -> service.create(APP_ID, request(), "click-2", user));

        verify(chatHistoryService, never()).saveMessage(anyLong(), anyLong(),
                anyString(), anyString(), any());
        verify(outboxMapper, never()).insertSelective(any());
    }

    @Test
    @DisplayName("首次创建在同一事务路径写入用户消息、Job 和 Outbox")
    void create_persistsJobAndOutbox() {
        when(jobMapper.selectOneByQuery(any())).thenReturn(null);
        when(jobMapper.selectCountByQuery(any())).thenReturn(0L);
        when(jobMapper.insertSelective(any())).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(9002L);
            return 1;
        });
        ChatHistory history = new ChatHistory();
        history.setId(8001L);
        when(chatHistoryService.saveMessage(APP_ID, USER_ID, request().getMessage(), "user", null))
                .thenReturn(history);
        when(outboxMapper.insertSelective(any())).thenReturn(1);

        GenerationJobVO result = service.create(APP_ID, request(), "click-3", user);

        assertEquals(9002L, result.getJobId());
        assertEquals("QUEUED", result.getStatus());
        verify(outboxMapper).insertSelective(any());
        ArgumentCaptor<GenerationJob> jobCaptor =
                ArgumentCaptor.forClass(GenerationJob.class);
        verify(jobMapper).insertSelective(jobCaptor.capture());
        assertEquals("html-static", jobCaptor.getValue().getStackProfileId());
        assertEquals("1", jobCaptor.getValue().getStackProfileVersion());
        assertEquals("[]", jobCaptor.getValue().getSkillsSnapshotJson());
    }

    @Test
    @DisplayName("运行中取消只写 CANCEL_REQUESTED，由 Worker 跨进程协作退出")
    void cancelRunningJobUsesPersistentCancelSignal() {
        GenerationJob running = GenerationJob.builder()
                .id(9003L)
                .workspaceId(WORKSPACE_ID)
                .appId(APP_ID)
                .requestedBy(USER_ID)
                .status("RUNNING")
                .currentStage("GENERATE")
                .build();
        when(jobMapper.selectOneById(9003L)).thenReturn(running);
        when(jobMapper.cancelQueued(9003L)).thenReturn(0);
        when(jobMapper.requestCancel(9003L)).thenReturn(1);

        GenerationJobVO result = service.cancel(9003L, user);

        assertNotNull(result);
        verify(jobMapper).requestCancel(9003L);
        verify(eventService).append(eq(9003L), eq("cancel_requested"),
                eq("CANCEL_REQUESTED"), any());
        verify(workspaceService).requireMember(WORKSPACE_ID, USER_ID);
    }

    @Test
    @DisplayName("Worker 通过数据库状态轮询跨进程取消")
    void detectsPersistentCancelRequest() {
        when(jobMapper.selectCountByQuery(any())).thenReturn(1L);

        assertTrue(service.isCancelRequested(9003L));
    }

    @Test
    @DisplayName("按 App 返回最近 Job，供刷新和断线后恢复失败态")
    void returnsLatestJobForAuthorizedApp() {
        GenerationJob latest = GenerationJob.builder()
                .id(9010L)
                .workspaceId(WORKSPACE_ID)
                .appId(APP_ID)
                .requestedBy(USER_ID)
                .status("FAILED")
                .currentStage("FAILED")
                .errorType("BUSINESS")
                .errorMessage("build failed")
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        when(jobMapper.selectOneByQuery(any())).thenReturn(latest);

        GenerationJobVO result = service.getLatestForApp(APP_ID, user);

        assertEquals(9010L, result.getJobId());
        assertEquals("FAILED", result.getStatus());
        assertEquals("build failed", result.getErrorMessage());
        verify(workspaceService).requireMember(WORKSPACE_ID, USER_ID);
    }

    private GenerationJobCreateRequest request() {
        GenerationJobCreateRequest request = new GenerationJobCreateRequest();
        request.setMessage("生成一个摄影网站");
        request.setReasoningLevel("standard");
        request.setCodeGenType("html");
        request.setStackProfileId("html-static");
        TemplateBundle template = templateRegistry.get("default-neutral");
        request.setTemplateHint("default-neutral");
        request.setTemplateBundleId(template.id());
        request.setTemplateVersion(template.version());
        request.setTemplateHash(template.effectiveHash());
        request.setRequestType("generate");
        request.setVisibility("private");
        return request;
    }

    private String hashOf(GenerationJobCreateRequest request) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(new ObjectMapper().writeValueAsBytes(request));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
