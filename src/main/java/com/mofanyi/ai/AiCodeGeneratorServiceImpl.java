package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.core.CodeParser;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

public class AiCodeGeneratorServiceImpl implements AiCodeGeneratorService {

    private final ChatClient chatClient;
    private final String singleHtmlPrompt;
    private final String multiFilePrompt;
    private final CodeParser codeParser;

    public AiCodeGeneratorServiceImpl(ChatModel chatModel,
                                      String singleHtmlPrompt,
                                      String multiFilePrompt,
                                      CodeParser codeParser) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.codeParser = codeParser;
    }

    @Override
    public HtmlCodeResult generateHtmlCode(String userMessage) {
        checkUserMessage(userMessage);
        String fullPrompt = singleHtmlPrompt + "\n\n" + codeParser.getHtmlFormat()
                + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage;
        String content = chatClient.prompt().user(fullPrompt).call().content();
        return codeParser.parseHtmlCode(content);
    }

    @Override
    public MultiFileCodeResult generateMultiFileCode(String userMessage) {
        checkUserMessage(userMessage);
        String fullPrompt = multiFilePrompt + "\n\n" + codeParser.getMultiFileFormat()
                + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage;
        String content = chatClient.prompt().user(fullPrompt).call().content();
        return codeParser.parseMultiFileCode(content);
    }

    @Override
    public Flux<String> streamGenerateHtmlCode(String userMessage) {
        checkUserMessage(userMessage);
        String fullPrompt = singleHtmlPrompt + "\n\n" + codeParser.getHtmlFormat()
                + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage;
        return chatClient.prompt().user(fullPrompt).stream().content();
    }

    @Override
    public Flux<String> streamGenerateMultiFileCode(String userMessage) {
        checkUserMessage(userMessage);
        String fullPrompt = multiFilePrompt + "\n\n" + codeParser.getMultiFileFormat()
                + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage;
        return chatClient.prompt().user(fullPrompt).stream().content();
    }

    /**
     * 校验用户需求不为空
     */
    private void checkUserMessage(String userMessage) {
        ThrowUtils.throwIf(StrUtil.isBlank(userMessage),
                ErrorCode.PARAMS_ERROR, "用户需求不能为空");
    }
}
