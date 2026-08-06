package com.mofanyi.ai;

import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import reactor.core.publisher.Flux;

public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */

    HtmlCodeResult generateHtmlCode(String userMessage);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    MultiFileCodeResult generateMultiFileCode(String userMessage);

    /**
     * 流式生成 HTML 代码，返回 token 流
     *
     * @param userMessage 用户消息
     * @return 模型逐段输出的 token 流
     */
    Flux<String> streamGenerateHtmlCode(String userMessage);

    /**
     * 流式生成多文件代码，返回 token 流
     *
     * @param userMessage 用户消息
     * @return 模型逐段输出的 token 流
     */
    Flux<String> streamGenerateMultiFileCode(String userMessage);
}
