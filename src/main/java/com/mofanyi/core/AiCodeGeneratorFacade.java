package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.AiCodeGeneratorService;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.AppGenerateStatusEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppService;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 代码生成门面，对外屏蔽 AI 生成与文件落盘的实现细节。
 * 调用方只需传入用户需求描述，即可拿到生成并落盘后的项目结果。
 * 同时维护生成状态（App.generateStatus）并支持手动停止流式生成。
 */
@Component
public class AiCodeGeneratorFacade {

    /**
     * 进行中的生成任务：appId → Disposable
     */
    private static final Map<Long, Disposable> GENERATING_TASKS = new ConcurrentHashMap<>();

    private final AiCodeGeneratorService aiCodeGeneratorService;
    private final CodeFileWriteService codeFileWriteService;
    private final CodeParser codeParser;
    private final AppService appService;

    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService,
                                 CodeFileWriteService codeFileWriteService,
                                 CodeParser codeParser,
                                 AppService appService) {
        this.aiCodeGeneratorService = aiCodeGeneratorService;
        this.codeFileWriteService = codeFileWriteService;
        this.codeParser = codeParser;
        this.appService = appService;
    }

    /**
     * 生成单文件 HTML 项目并落盘
     *
     * @param userMessage 用户需求描述
     * @param appId       应用 id（用于项目文件夹命名）
     * @return 生成结果 VO（含项目路径与代码）
     */
    public AiCodeGenResultVO generateHtmlProject(String userMessage, Long appId) {
        checkGenerateParams(userMessage, appId);
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        try {
            HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
            File projectDir = codeFileWriteService.saveHtmlCode(result, appId);
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
            return toVo(projectDir, result.getHtmlCode(), null, null, result.getDescription());
        } catch (RuntimeException e) {
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
            throw e;
        }
    }

    /**
     * 生成多文件项目并落盘
     *
     * @param userMessage 用户需求描述
     * @param appId       应用 id（用于项目文件夹命名）
     * @return 生成结果 VO（含项目路径与代码）
     */
    public AiCodeGenResultVO generateMultiFileProject(String userMessage, Long appId) {
        checkGenerateParams(userMessage, appId);
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        try {
            MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
            File projectDir = codeFileWriteService.saveMultiFileCode(result, appId);
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
            return toVo(projectDir, result.getHtmlCode(), result.getCssCode(), result.getJsCode(),
                    result.getDescription());
        } catch (RuntimeException e) {
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
            throw e;
        }
    }

    /**
     * 流式生成单文件 HTML 项目，通过回调逐段接收 token、完成结果 VO 或异常
     *
     * @param userMessage 用户需求描述
     * @param appId       应用 id（用于项目文件夹命名）
     * @param callback    流式回调（token / done / error）
     */
    public void streamGenerateHtmlProject(String userMessage, Long appId, StreamCallback callback) {
        checkGenerateParams(userMessage, appId);
        ThrowUtils.throwIf(callback == null, ErrorCode.PARAMS_ERROR);
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        StringBuilder raw = new StringBuilder();
        Disposable disposable = aiCodeGeneratorService.streamGenerateHtmlCode(userMessage)
                .subscribe(
                        token -> {
                            raw.append(token);
                            callback.onToken(token);
                        },
                        error -> {
                            GENERATING_TASKS.remove(appId);
                            // 网络断连/异常时：若已累积的 token 能解析出代码，则兜底落盘返回，
                            // 避免用户白等。解析不出才标记失败。
                            if (!tryRecoverHtml(raw.toString(), appId, callback)) {
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
                                callback.onError(error);
                            }
                        },
                        () -> {
                            GENERATING_TASKS.remove(appId);
                            try {
                                HtmlCodeResult result = codeParser.parseHtmlCode(raw.toString());
                                File projectDir = codeFileWriteService.saveHtmlCode(result, appId);
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
                                callback.onDone(toVo(projectDir, result.getHtmlCode(), null, null,
                                        result.getDescription()));
                            } catch (RuntimeException e) {
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
                                callback.onError(e);
                            }
                        });
        GENERATING_TASKS.put(appId, disposable);
    }

    /**
     * 流式生成多文件项目，通过回调逐段接收 token、完成结果 VO 或异常
     *
     * @param userMessage 用户需求描述
     * @param appId       应用 id（用于项目文件夹命名）
     * @param callback    流式回调（token / done / error）
     */
    public void streamGenerateMultiFileProject(String userMessage, Long appId, StreamCallback callback) {
        checkGenerateParams(userMessage, appId);
        ThrowUtils.throwIf(callback == null, ErrorCode.PARAMS_ERROR);
        appService.updateGenerateStatus(appId, AppGenerateStatusEnum.GENERATING.getValue());
        StringBuilder raw = new StringBuilder();
        Disposable disposable = aiCodeGeneratorService.streamGenerateMultiFileCode(userMessage)
                .subscribe(
                        token -> {
                            raw.append(token);
                            callback.onToken(token);
                        },
                        error -> {
                            GENERATING_TASKS.remove(appId);
                            // 网络断连/异常时：若已累积的 token 能解析出代码，则兜底落盘返回
                            if (!tryRecoverMultiFile(raw.toString(), appId, callback)) {
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
                                callback.onError(error);
                            }
                        },
                        () -> {
                            GENERATING_TASKS.remove(appId);
                            try {
                                MultiFileCodeResult result = codeParser.parseMultiFileCode(raw.toString());
                                File projectDir = codeFileWriteService.saveMultiFileCode(result, appId);
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
                                callback.onDone(toVo(projectDir, result.getHtmlCode(), result.getCssCode(),
                                        result.getJsCode(), result.getDescription()));
                            } catch (RuntimeException e) {
                                appService.updateGenerateStatus(appId, AppGenerateStatusEnum.FAILED.getValue());
                                callback.onError(e);
                            }
                        });
        GENERATING_TASKS.put(appId, disposable);
    }

    /**
     * 单文件 HTML：网络异常时用已累积的 token 容错解析并落盘。
     *
     * @return 是否成功兜底（已 onDone）
     */
    private boolean tryRecoverHtml(String raw, Long appId, StreamCallback callback) {
        try {
            HtmlCodeResult result = codeParser.parseHtmlCode(raw);
            if (result == null || StrUtil.isBlank(result.getHtmlCode())) {
                return false;
            }
            File projectDir = codeFileWriteService.saveHtmlCode(result, appId);
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
            callback.onDone(toVo(projectDir, result.getHtmlCode(), null, null, result.getDescription()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 多文件：网络异常时用已累积的 token 容错解析并落盘。
     *
     * @return 是否成功兜底（已 onDone）
     */
    private boolean tryRecoverMultiFile(String raw, Long appId, StreamCallback callback) {
        try {
            MultiFileCodeResult result = codeParser.parseMultiFileCode(raw);
            if (result == null || StrUtil.isBlank(result.getHtmlCode())) {
                return false;
            }
            File projectDir = codeFileWriteService.saveMultiFileCode(result, appId);
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.DONE.getValue());
            callback.onDone(toVo(projectDir, result.getHtmlCode(), result.getCssCode(),
                    result.getJsCode(), result.getDescription()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 手动停止指定应用的生成任务
     *
     * @param appId 应用 id
     */
    public void stopGenerate(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        Disposable disposable = GENERATING_TASKS.remove(appId);
        if (disposable != null) {
            disposable.dispose();
            appService.updateGenerateStatus(appId, AppGenerateStatusEnum.STOPPED.getValue());
        }
    }

    /**
     * 校验生成接口入参
     */
    private void checkGenerateParams(String userMessage, Long appId) {
        ThrowUtils.throwIf(StrUtil.isBlank(userMessage), ErrorCode.PARAMS_ERROR, "用户需求不能为空");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
    }

    /**
     * 组装生成结果 VO
     */
    private AiCodeGenResultVO toVo(File projectDir, String htmlCode,
                                   String cssCode, String jsCode, String description) {
        AiCodeGenResultVO vo = new AiCodeGenResultVO();
        vo.setProjectPath(projectDir.getAbsolutePath());
        vo.setHtmlCode(htmlCode);
        vo.setCssCode(cssCode);
        vo.setJsCode(jsCode);
        vo.setDescription(description);
        return vo;
    }
}
