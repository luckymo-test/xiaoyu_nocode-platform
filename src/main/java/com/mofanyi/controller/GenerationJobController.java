package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.job.GenerationJobCreateRequest;
import com.mofanyi.model.dto.job.PlanConfirmRequest;
import com.mofanyi.model.dto.job.PlanReviseRequest;
import com.mofanyi.model.vo.GenerationJobVO;
import com.mofanyi.entity.AgentPlan;
import com.mofanyi.entity.AgentStep;
import com.mofanyi.entity.AgentToolCall;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.GenerationJobSseService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 持久化生成任务控制面。创建与订阅分离，SSE 只读。
 */
@RestController
@RequestMapping("/v1")
public class GenerationJobController {

    private final GenerationJobService jobService;
    private final GenerationJobSseService sseService;
    private final UserService userService;

    public GenerationJobController(GenerationJobService jobService,
                                   GenerationJobSseService sseService,
                                   UserService userService) {
        this.jobService = jobService;
        this.sseService = sseService;
        this.userService = userService;
    }

    @PostMapping("/apps/{appId}/generation-jobs")
    public ResponseEntity<BaseResponse<GenerationJobVO>> create(
            @PathVariable Long appId,
            @RequestBody GenerationJobCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(httpRequest);
        GenerationJobVO job = jobService.create(appId, request, idempotencyKey, loginUser);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResultUtils.success(job));
    }

    @GetMapping("/generation-jobs/{jobId}")
    public BaseResponse<GenerationJobVO> get(@PathVariable Long jobId,
                                             HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(jobService.getVO(jobId, loginUser));
    }

    @GetMapping(value = "/generation-jobs/{jobId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long jobId,
                             @RequestParam(value = "after", defaultValue = "0") long after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        jobService.getAuthorized(jobId, loginUser);
        long cursor = Math.max(after, parseSequence(lastEventId));
        return sseService.open(jobId, cursor, false);
    }

    @PostMapping("/generation-jobs/{jobId}/cancel")
    public BaseResponse<GenerationJobVO> cancel(@PathVariable Long jobId,
                                                HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(jobService.cancel(jobId, loginUser));
    }

    @PostMapping("/generation-jobs/{jobId}/retry")
    public BaseResponse<GenerationJobVO> retry(@PathVariable Long jobId,
                                               HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(jobService.retryFailed(jobId, loginUser));
    }

    @PostMapping("/generation-jobs/{jobId}/pause")
    public BaseResponse<GenerationJobVO> pause(@PathVariable Long jobId,
                                               HttpServletRequest request) {
        return ResultUtils.success(jobService.pause(jobId, userService.getLoginUser(request)));
    }

    @PostMapping("/generation-jobs/{jobId}/resume")
    public BaseResponse<GenerationJobVO> resume(@PathVariable Long jobId,
                                                HttpServletRequest request) {
        return ResultUtils.success(jobService.resume(jobId, userService.getLoginUser(request)));
    }

    @PostMapping("/generation-jobs/{jobId}/confirm")
    public BaseResponse<GenerationJobVO> confirm(
            @PathVariable Long jobId,
            @RequestBody PlanConfirmRequest body,
            HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(jobService.confirmPlan(
                jobId, body.getPlanHash(), userService.getLoginUser(request)));
    }

    @PostMapping("/generation-jobs/{jobId}/plan/revise")
    public BaseResponse<GenerationJobVO> revisePlan(
            @PathVariable Long jobId,
            @RequestBody PlanReviseRequest body,
            HttpServletRequest request) {
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(jobService.revisePlan(
                jobId, body.getPlanHash(), body.getComment(),
                userService.getLoginUser(request)));
    }

    @GetMapping("/generation-jobs/{jobId}/plan")
    public BaseResponse<AgentPlan> plan(@PathVariable Long jobId,
                                        HttpServletRequest request) {
        return ResultUtils.success(jobService.getLatestPlan(
                jobId, userService.getLoginUser(request)));
    }

    @GetMapping("/generation-jobs/{jobId}/steps")
    public BaseResponse<List<AgentStep>> steps(@PathVariable Long jobId,
                                               HttpServletRequest request) {
        return ResultUtils.success(jobService.listSteps(
                jobId, userService.getLoginUser(request)));
    }

    @GetMapping("/generation-jobs/{jobId}/tools")
    public BaseResponse<List<AgentToolCall>> tools(@PathVariable Long jobId,
                                                   HttpServletRequest request) {
        return ResultUtils.success(jobService.listToolCalls(
                jobId, userService.getLoginUser(request)));
    }

    @PostMapping("/generation-jobs/{jobId}/steps/{stepKey}/retry")
    public BaseResponse<GenerationJobVO> retryStep(
            @PathVariable Long jobId,
            @PathVariable String stepKey,
            HttpServletRequest request) {
        return ResultUtils.success(jobService.retryStep(
                jobId, stepKey, userService.getLoginUser(request)));
    }

    private long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
