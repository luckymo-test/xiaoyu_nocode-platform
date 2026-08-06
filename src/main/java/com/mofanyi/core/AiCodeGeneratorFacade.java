package com.mofanyi.core;

import com.mofanyi.ai.AiCodeGeneratorService;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * AI 代码生成门面，对外屏蔽 AI 生成与文件落盘的实现细节。
 * 调用方只需传入用户需求描述，即可拿到生成并落盘后的项目结果。
 */
@Component
public class AiCodeGeneratorFacade {

    private final AiCodeGeneratorService aiCodeGeneratorService;
    private final CodeFileWriteService codeFileWriteService;
    private final CodeParser codeParser;

    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService,
                                 CodeFileWriteService codeFileWriteService,
                                 CodeParser codeParser) {
        this.aiCodeGeneratorService = aiCodeGeneratorService;
        this.codeFileWriteService = codeFileWriteService;
        this.codeParser = codeParser;
    }

    /**
     * 生成单文件 HTML 项目并落盘
     *
     * @param userMessage 用户需求描述
     * @return 生成结果 VO（含项目路径与代码）
     */
    public AiCodeGenResultVO generateHtmlProject(String userMessage) {
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
        File projectDir = codeFileWriteService.saveHtmlCode(result);
        return toVo(projectDir, result.getHtmlCode(), null, null, result.getDescription());
    }

    /**
     * 生成多文件项目并落盘
     *
     * @param userMessage 用户需求描述
     * @return 生成结果 VO（含项目路径与代码）
     */
    public AiCodeGenResultVO generateMultiFileProject(String userMessage) {
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
        File projectDir = codeFileWriteService.saveMultiFileCode(result);
        return toVo(projectDir, result.getHtmlCode(), result.getCssCode(), result.getJsCode(),
                result.getDescription());
    }

    /**
     * 流式生成单文件 HTML 项目，通过回调逐段接收 token、完成结果 VO 或异常
     *
     * @param userMessage 用户需求描述
     * @param callback    流式回调（token / done / error）
     */
    public void streamGenerateHtmlProject(String userMessage, StreamCallback callback) {
        StringBuilder raw = new StringBuilder();
        aiCodeGeneratorService.streamGenerateHtmlCode(userMessage)
                .subscribe(
                        token -> {
                            raw.append(token);
                            callback.onToken(token);
                        },
                        error -> callback.onError(error),
                        () -> {
                            try {
                                HtmlCodeResult result = codeParser.parseHtmlCode(raw.toString());
                                File projectDir = codeFileWriteService.saveHtmlCode(result);
                                callback.onDone(toVo(projectDir, result.getHtmlCode(), null, null,
                                        result.getDescription()));
                            } catch (RuntimeException e) {
                                callback.onError(e);
                            }
                        });
    }

    /**
     * 流式生成多文件项目，通过回调逐段接收 token、完成结果 VO 或异常
     *
     * @param userMessage 用户需求描述
     * @param callback    流式回调（token / done / error）
     */
    public void streamGenerateMultiFileProject(String userMessage, StreamCallback callback) {
        StringBuilder raw = new StringBuilder();
        aiCodeGeneratorService.streamGenerateMultiFileCode(userMessage)
                .subscribe(
                        token -> {
                            raw.append(token);
                            callback.onToken(token);
                        },
                        error -> callback.onError(error),
                        () -> {
                            try {
                                MultiFileCodeResult result = codeParser.parseMultiFileCode(raw.toString());
                                File projectDir = codeFileWriteService.saveMultiFileCode(result);
                                callback.onDone(toVo(projectDir, result.getHtmlCode(), result.getCssCode(),
                                        result.getJsCode(), result.getDescription()));
                            } catch (RuntimeException e) {
                                callback.onError(e);
                            }
                        });
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
