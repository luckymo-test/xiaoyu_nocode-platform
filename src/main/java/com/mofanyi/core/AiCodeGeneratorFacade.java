package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.SiteModifyAgent;
import com.mofanyi.ai.VueProjectAgent;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationCancelledException;
import com.mofanyi.ai.task.GenerationTask;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.AppGenerateStatusEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 代码生成门面，对外屏蔽 AI 生成与文件落盘的实现细节。
 * 调用方只需传入用户需求描述，即可拿到生成并落盘后的项目结果。
 * 同时维护生成状态（App.generateStatus）并支持手动停止流式生成。
 */
@Slf4j
@Component
@Conditional(GenerationExecutionCondition.class)
public class AiCodeGeneratorFacade {

    private final CodeFileWriteService codeFileWriteService;
    private final AppService appService;
    private final VueProjectAgent vueProjectAgent;
    private final SiteRepairAgent siteRepairAgent;
    private final SiteModifyAgent siteModifyAgent;
    private final ReasoningProfileRegistry reasoningProfiles;
    /**
     * 生成任务注册表：负责 appId 级互斥与"停止生成"的取消句柄
     */
    private final GenerationTaskRegistry taskRegistry;
    private final BrowserVerifierPort renderVerifier;

    /**
     * 渲染修复执行器：交付后异步修复（见 {@link #runRenderFix}）
     */
    private final ExecutorService renderFixExecutor;

    /**
     * 渲染修复状态：appId → ok / fixing / fixed / still_has_problems / skipped / error
     */
    private final Map<Long, String> renderFixStatus = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * 是否开启交付前渲染验证（plan-agent-pipeline P0-4）
     */
    @org.springframework.beans.factory.annotation.Value("${codegen.verify.enabled:true}")
    private boolean renderVerifyEnabled;
    /**
     * Vue 工程生成线程池（Spring 容器管理，参数见 thread-pool.vue 配置）
     */
    private final ThreadPoolExecutor vueThreadPoolExecutor;

    @Autowired(required = false)
    private VisualRegressionService visualRegressionService;

    public AiCodeGeneratorFacade(CodeFileWriteService codeFileWriteService,
                                 AppService appService,
                                 VueProjectAgent vueProjectAgent,
                                 SiteRepairAgent siteRepairAgent,
                                 SiteModifyAgent siteModifyAgent,
                                 ReasoningProfileRegistry reasoningProfiles,
                                 GenerationTaskRegistry taskRegistry,
                                 BrowserVerifierPort renderVerifier,
                                 @Qualifier("renderFixExecutor") ExecutorService renderFixExecutor,
                                 ThreadPoolExecutor vueThreadPoolExecutor) {
        this.codeFileWriteService = codeFileWriteService;
        this.appService = appService;
        this.vueProjectAgent = vueProjectAgent;
        this.siteRepairAgent = siteRepairAgent;
        this.siteModifyAgent = siteModifyAgent;
        this.reasoningProfiles = reasoningProfiles;
        this.taskRegistry = taskRegistry;
        this.renderVerifier = renderVerifier;
        this.renderFixExecutor = renderFixExecutor;
        this.vueThreadPoolExecutor = vueThreadPoolExecutor;
    }

    /**
     * DevTools 重启或应用关闭时，先取消生成并中断线程池任务，避免工作线程在数据源关闭后继续写状态。
     */
    @PreDestroy
    public void shutdownGenerationTasks() {
        shuttingDown.set(true);
        taskRegistry.cancelAll();
        vueThreadPoolExecutor.shutdownNow();
        renderFixExecutor.shutdownNow();
        log.info("[agent] 正在关闭生成线程池");
    }

    /**
     * 生成单文件 HTML 项目并落盘
     *
     * @param userMessage 用户需求描述
     * @param appId       应用 id（用于项目文件夹命名）
     * @return 生成结果 VO（含项目路径与代码）
     */
    public AiCodeGenResultVO generate(CodeGenTypeEnum type, String userMessage, Long appId) {
        return generate(type, userMessage, appId, reasoningProfiles.defaultReasoningLevel());
    }

    public AiCodeGenResultVO generate(CodeGenTypeEnum type, String userMessage, Long appId,
                                      ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        checkGenerateParams(userMessage, appId);
        ThrowUtils.throwIf(reasoningLevel == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");
        // 互斥：同一 appId 只允许一个生成在跑（plan-refactor P0-2）。原先 vue 非流式入口漏了这一层，现统一补齐。
        GenerationTask task = acquireOrThrow(appId, callTypeOf(type));
        try {
            task.bind(Thread.currentThread());
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
            VueProjectAgent.GenerateResult result = vueProjectAgent.generate(
                    type, appId, userMessage, callContext(appId, callTypeOf(type), null),
                    null, reasoningLevel);
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
            afterGenerate(type, appId, null);
            AiCodeGenResultVO vo = readResult(type, appId);
            applyUsage(vo, result);
            return vo;
        } catch (RuntimeException e) {
            appService.updateGenerateStatus(appId, failureStatus(e));
            throw e;
        } finally {
            taskRegistry.release(appId, task);
        }
    }

    /**
     * 生成后的类型分支——**统一入口里唯一按类型分叉的地方**：
     * html / multi_file 交付前要做渲染验证（plan-agent-pipeline P0-4）；vue 用构建产物预览，只失效旧 dist。
     * 抽成一个方法，正是为了让"三条生成路径"共用同一套收尾。
     */
    private void afterGenerate(CodeGenTypeEnum type, Long appId, Long jobId) {
        if (type == CodeGenTypeEnum.VUE) {
            // 源码更新后失效旧构建产物，下次预览/部署重新构建
            codeFileWriteService.deleteVueDist(appId);
            return;
        }
        verifyAndScheduleFix(type, appId, jobId);
    }

    /**
     * 流式生成（html / multi_file / vue 统一入口）：在独立线程执行通用 agent，
     * 逐文件进度（agent_progress）、思考增量（reasoning）与最终结果 VO（done）通过回调返回。
     */
    public void streamGenerate(CodeGenTypeEnum type, String userMessage, Long appId, StreamCallback callback) {
        streamGenerate(type, userMessage, appId, callback,
                reasoningProfiles.defaultReasoningLevel(), null);
    }

    public void streamGenerate(CodeGenTypeEnum type, String userMessage, Long appId, StreamCallback callback,
                               ReasoningLevel reasoningLevel) {
        streamGenerate(type, userMessage, appId, callback, reasoningLevel, null);
    }

    public void streamGenerate(CodeGenTypeEnum type, String userMessage, Long appId, StreamCallback callback,
                               ReasoningLevel reasoningLevel, Long jobId) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        checkGenerateParams(userMessage, appId);
        ThrowUtils.throwIf(callback == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(reasoningLevel == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");
        GenerationTask task = acquireOrThrow(appId, callTypeOf(type));
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        vueThreadPoolExecutor.execute(() -> {
            task.bind(Thread.currentThread());
            try {
                VueProjectAgent.GenerateResult result = vueProjectAgent.generate(type, appId, userMessage,
                        callContext(appId, callTypeOf(type), jobId),
                        new VueProjectAgent.VueProgressListener() {
                            @Override
                            public void onFileWritten(String path, int size, String content) {
                                callback.onToken("{\"path\":" + toJsonString(path)
                                        + ",\"content\":" + toJsonString(content) + "}");
                            }

                            @Override
                            public void onReasoning(String reasoning) {
                                callback.onReasoning(reasoning);
                            }
                        }, reasoningLevel);
                if (shuttingDown.get()) {
                    log.info("[agent] 应用正在关闭，跳过生成完成状态写入，appId={}", appId);
                    return;
                }
                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
                afterGenerate(type, appId, jobId);
                AiCodeGenResultVO vo = readResult(type, appId);
                applyUsage(vo, result);
                callback.onDone(vo);
            } catch (RuntimeException e) {
                if (shuttingDown.get()) {
                    log.info("[agent] 应用关闭导致生成中止，跳过失败状态写入，appId={}", appId);
                    return;
                }
                try {
                    appService.updateGenerateStatus(appId, failureStatus(e));
                } catch (RuntimeException statusError) {
                    e.addSuppressed(statusError);
                    log.warn("[agent] 写入失败状态时再次异常，appId={}", appId, statusError);
                }
                log.error("[agent] 生成失败，type={}, appId={}", type, appId, e);
                callback.onError(e);
            } finally {
                taskRegistry.release(appId, task);
            }
        });
    }

    /**
     * 把 agent 返回的 token 用量挂到结果 VO 上（前端展示元信息）
     */
    private void applyUsage(AiCodeGenResultVO vo, VueProjectAgent.GenerateResult result) {
        if (vo == null || result == null || result.getUsage() == null) {
            return;
        }
        vo.setPromptTokens(result.getUsage().promptTokens());
        vo.setCompletionTokens(result.getUsage().completionTokens());
        vo.setTotalTokens(result.getUsage().totalTokens());
    }

    /**
     * 构造 AI 调用监控上下文（解析应用归属用户；失败不影响主流程）
     */
    private AiCallContext callContext(Long appId, String callType) {
        return callContext(appId, callType, null);
    }

    private AiCallContext callContext(Long appId, String callType, Long jobId) {
        Long userId = null;
        try {
            var app = appService.getById(appId);
            if (app != null) {
                userId = app.getUserId();
            }
        } catch (RuntimeException e) {
            log.warn("解析应用归属用户失败，appId={}", appId, e);
        }
        return AiCallContext.ofJob(appId, userId, callType, jobId);
    }

    private String callTypeOf(CodeGenTypeEnum type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type) {
            case HTML -> "html";
            case MULTI_FILE -> "multi_file";
            case VUE -> "vue";
        };
    }

    /**
     * 从项目目录读取生成结果 VO（html / multi_file / vue 统一入口）
     */
    private AiCodeGenResultVO readResult(CodeGenTypeEnum type, Long appId) {
        AiCodeGenResultVO vo = codeFileWriteService.readProjectCode(type, appId);
        if (vo == null) {
            throw new RuntimeException("生成结果读取失败");
        }
        return vo;
    }

    /**
     * 手动停止指定应用的生成任务
     *
     * @param appId 应用 id
     */
    public void stopGenerate(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        if (taskRegistry.cancel(appId)) {
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.STOPPED.getValue());
        }
    }

    /**
     * 可视化修改流式接口：在独立线程执行修改 agent，逐文件进度（agent_progress）、思考增量（reasoning）
     * 与最终结果 VO（done）通过回调返回。vue 用构建产物预览，暂不支持可视化修改（入参校验前过滤）。
     *
     * @param type           代码生成类型（html / multi_file）
     * @param appId          应用 id
     * @param elementContext 前端点选生成的元素上下文，可为空串
     * @param instruction    用户自然语言修改意图
     * @param callback       回调
     */
    public void streamModifyProject(CodeGenTypeEnum type, Long appId, String elementContext,
                                    String instruction, StreamCallback callback) {
        streamModifyProject(type, appId, elementContext, instruction, callback,
                reasoningProfiles.defaultReasoningLevel());
    }

    public void streamModifyProject(CodeGenTypeEnum type, Long appId, String elementContext,
                                    String instruction, StreamCallback callback,
                                    ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(type == null || type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持可视化修改");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");
        ThrowUtils.throwIf(callback == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(reasoningLevel == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");
        GenerationTask task = acquireOrThrow(appId, "modify");
        vueThreadPoolExecutor.execute(() -> {
            task.bind(Thread.currentThread());
            try {
                TokenUsageAccumulator usage = new TokenUsageAccumulator();
                siteModifyAgent.modifyGeneratedSite(type, appId, elementContext, instruction, usage,
                        callContext(appId, "modify"),
                        new VueProjectAgent.VueProgressListener() {
                            @Override
                            public void onFileWritten(String path, int size, String content) {
                                callback.onToken("{\"path\":" + toJsonString(path)
                                        + ",\"content\":" + toJsonString(content) + "}");
                            }

                            @Override
                            public void onReasoning(String reasoning) {
                                callback.onReasoning(reasoning);
                            }
                        }, reasoningLevel);
                AiCodeGenResultVO vo = readResult(type, appId);
                vo.setPromptTokens(usage.promptTokens());
                vo.setCompletionTokens(usage.completionTokens());
                vo.setTotalTokens(usage.totalTokens());
                callback.onDone(vo);
            } catch (RuntimeException e) {
                log.error("[modify] 可视化修改失败，type={}, appId={}", type, appId, e);
                callback.onError(e);
            } finally {
                taskRegistry.release(appId, task);
            }
        });
    }

    /**
     * 还原源码（可视化修改的"撤销"）：写回给定文件快照并返回还原后的项目 VO。
     *
     * @param type  代码生成类型（html / multi_file）
     * @param appId 应用 id
     * @param files 文件快照（path -> content）
     * @return 还原后的项目结果 VO
     */
    public AiCodeGenResultVO restoreSource(CodeGenTypeEnum type, Long appId, Map<String, String> files) {
        ThrowUtils.throwIf(type == null || type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "该模式不支持撤销");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(files == null || files.isEmpty(), ErrorCode.PARAMS_ERROR, "还原内容为空");
        siteModifyAgent.restoreFiles(type, appId, files);
        return readResult(type, appId);
    }

    /**
     * 简易 JSON 字符串转义（用于把文件路径/内容包装进 JSON 事件）
     */
    private String toJsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 交付前渲染验证（同步、快）+ 异步修复（plan-agent-pipeline P0-4）。
     *
     * <p>只做**检测**：检测很快，结果随生成一起返回；发现问题后把"修复 + 复验"丢到
     * {@code renderFixExecutor} 异步执行，不让用户等 2~3 分钟。
     *
     * <p>验证/修复失败都不影响生成结果返回，只记日志与落盘报告。
     */
    private void verifyAndScheduleFix(CodeGenTypeEnum type, Long appId, Long jobId) {
        if (!renderVerifyEnabled || type == null || type == CodeGenTypeEnum.VUE) {
            return;
        }
        try {
            RenderVerifier.RenderReport report =
                    renderVerifier.verify(type, appId, "before", jobId);
            renderVerifier.persistReport(type, appId, report, "before");
            if (!report.isUsable() || !report.hasProblems()) {
                renderFixStatus.put(appId, "ok");
                return;
            }
            String issueText = report.toPromptText();
            // 问题清单进日志：修复前的问题不再只存在于喂给模型的上下文里（可追溯）
            log.info("[verify] appId={} {}，转异步修复。问题清单：\n{}", appId, report.summary(), issueText);
            renderFixStatus.put(appId, "fixing");
            renderFixExecutor.execute(() -> runRenderFix(type, appId, issueText, jobId));
        } catch (RuntimeException e) {
            log.warn("[verify] 渲染验证异常，已忽略（不影响生成结果），appId={}", appId, e);
            renderFixStatus.put(appId, "error");
        }
    }

    /**
     * 异步修复 + 复验。
     *
     * <p>用生成任务注册表做互斥：若用户已发起新一轮生成，则**跳过**修复，避免两处同时写同一工程目录
     * （P0-2 的互斥规则同样适用于这里）。修复期间该 appId 也会被互斥占用，用户的"停止生成"同样能取消它。
     */
    private void runRenderFix(CodeGenTypeEnum type, Long appId, String issueText, Long jobId) {
        GenerationTask task = acquireRenderFixTask(appId);
        if (task == null) {
            log.info("[verify] appId={} 已有任务在跑，跳过渲染修复", appId);
            renderFixStatus.put(appId, "skipped");
            return;
        }
        try {
            task.bind(Thread.currentThread());
            siteRepairAgent.fixRenderIssues(type, appId, issueText,
                    callContext(appId, "render_fix", jobId), null,
                    reasoningProfiles.defaultReasoningLevel());
            RenderVerifier.RenderReport after =
                    renderVerifier.verify(type, appId, "after", jobId);
            renderVerifier.persistReport(type, appId, after, "after");
            if (visualRegressionService != null) {
                visualRegressionService.compareAndPersist(codeFileWriteService, type, appId);
            }
            renderFixStatus.put(appId, after.hasProblems() ? "still_has_problems" : "fixed");
            log.info("[verify] appId={} 异步修复完成，复验：{}", appId, after.summary());
        } catch (RuntimeException e) {
            log.warn("[verify] 异步修复失败，appId={}", appId, e);
            renderFixStatus.put(appId, "error");
        } finally {
            taskRegistry.release(appId, task);
        }
    }

    /**
     * 获取渲染修复的任务占用。
     *
     * <p><b>为什么要重试</b>：异步修复是在生成流程**内部**提交的，那一刻生成任务自己还持有 appId 的互斥
     * （要到生成的 finally 才释放）。若立即抢占必然失败（实测状态直接变 skipped）。这里短暂重试，
     * 等生成释放后再抢；若用户紧接着发起了新一轮生成，则超时放弃——这正是我们要的互斥行为。
     */
    private GenerationTask acquireRenderFixTask(Long appId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            GenerationTask task = taskRegistry.tryAcquire(appId, "render_fix");
            if (task != null) {
                return task;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 渲染修复状态（供前端轮询）：ok / fixing / fixed / still_has_problems / skipped / error
     */
    public String renderFixStatus(Long appId) {
        return appId == null ? null : renderFixStatus.get(appId);
    }

    /**
     * 抢占 appId 的生成任务；已被占用时抛业务异常（同一应用互斥，见 plan-refactor P0-2）
     */
    private GenerationTask acquireOrThrow(Long appId, String type) {
        GenerationTask task = taskRegistry.tryAcquire(appId, type);
        ThrowUtils.throwIf(task == null, ErrorCode.OPERATION_ERROR, "该应用正在生成或修改中，请稍候再试");
        return task;
    }

    /**
     * 失败时的目标状态：手动停止记为 stopped，其余记为 failed（见 plan-refactor P0-1）
     */
    private String failureStatus(RuntimeException e) {
        return (e instanceof GenerationCancelledException
                ? AppGenerateStatusEnum.STOPPED
                : AppGenerateStatusEnum.FAILED).getValue();
    }

    private void checkGenerateParams(String userMessage, Long appId) {
        ThrowUtils.throwIf(StrUtil.isBlank(userMessage), ErrorCode.PARAMS_ERROR, "用户需求不能为空");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
    }
}
