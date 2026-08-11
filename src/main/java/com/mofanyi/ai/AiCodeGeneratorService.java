package com.mofanyi.ai;

import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import reactor.core.publisher.Flux;

public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户消息
     * @param appId       应用 id（作为对话记忆 conversationId）
     * @return 生成的代码结果
     */

    HtmlCodeResult generateHtmlCode(String userMessage, Long appId);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户消息
     * @param appId       应用 id（作为对话记忆 conversationId）
     * @return 生成的代码结果
     */
    MultiFileCodeResult generateMultiFileCode(String userMessage, Long appId);

    /**
     * 流式生成 HTML 代码，返回 token 流
     *
     * @param userMessage 用户消息
     * @param appId       应用 id（作为对话记忆 conversationId）
     * @return 模型逐段输出的 token 流
     */
    Flux<String> streamGenerateHtmlCode(String userMessage, Long appId);

    /**
     * 流式生成多文件代码，返回 token 流
     *
     * @param userMessage 用户消息
     * @param appId       应用 id（作为对话记忆 conversationId）
     * @return 模型逐段输出的 token 流
     */
    Flux<String> streamGenerateMultiFileCode(String userMessage, Long appId);
}
