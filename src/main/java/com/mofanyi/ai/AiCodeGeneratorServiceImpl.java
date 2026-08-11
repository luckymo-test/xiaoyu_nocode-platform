package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.core.CodeParser;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 基于 ChatClient 生成网站代码，并在生成前后手动维护 Redis 对话记忆：
 * 调用前读取该 app 的历史对话注入 prompt，调用后把本次用户消息与 AI 回复写入记忆。
 * 不使用 MessageChatMemoryAdvisor——它会将完整 system prompt 也存入记忆，污染上下文窗口。
 */
public class AiCodeGeneratorServiceImpl implements AiCodeGeneratorService {

    /**
     * 内存里累计的 AI 输出上限，防止异常流式输出写爆 Redis
     */
    private static final int MAX_MEMORY_TEXT = 100_000;

    private final ChatClient chatClient;
    private final String singleHtmlPrompt;
    private final String multiFilePrompt;
    private final CodeParser codeParser;
    private final ChatMemory chatMemory;

    public AiCodeGeneratorServiceImpl(ChatModel chatModel,
                                      String singleHtmlPrompt,
                                      String multiFilePrompt,
                                      CodeParser codeParser,
                                      ChatMemory chatMemory) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.codeParser = codeParser;
        this.chatMemory = chatMemory;
    }

    @Override
    public HtmlCodeResult generateHtmlCode(String userMessage, Long appId) {
        checkUserMessage(userMessage);
        String format = codeParser.getHtmlFormat();
        String content = chatClient.prompt()
                .system(singleHtmlPrompt)
                .messages(history(appId))
                .user(format + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage)
                .call()
                .content();
        remember(appId, userMessage, content);
        return codeParser.parseHtmlCode(content);
    }

    @Override
    public MultiFileCodeResult generateMultiFileCode(String userMessage, Long appId) {
        checkUserMessage(userMessage);
        String format = codeParser.getMultiFileFormat();
        String content = chatClient.prompt()
                .system(multiFilePrompt)
                .messages(history(appId))
                .user(format + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage)
                .call()
                .content();
        remember(appId, userMessage, content);
        return codeParser.parseMultiFileCode(content);
    }

    @Override
    public Flux<String> streamGenerateHtmlCode(String userMessage, Long appId) {
        checkUserMessage(userMessage);
        String format = codeParser.getHtmlFormat();
        // 先读取历史（不含本次消息），再写入用户消息供后续轮次读取，避免本次消息重复注入
        List<Message> hist = history(appId);
        addUserMessage(appId, userMessage);
        StringBuilder raw = new StringBuilder();
        return chatClient.prompt()
                .system(singleHtmlPrompt)
                .messages(hist)
                .user(format + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (raw.length() < MAX_MEMORY_TEXT) {
                        raw.append(token);
                    }
                })
                .doOnComplete(() -> addAssistantMessage(appId, raw.toString()))
                .doOnError(e -> {
                    // 流式失败：已写入的用户消息保留，供前端/历史展示
                });
    }

    @Override
    public Flux<String> streamGenerateMultiFileCode(String userMessage, Long appId) {
        checkUserMessage(userMessage);
        String format = codeParser.getMultiFileFormat();
        List<Message> hist = history(appId);
        addUserMessage(appId, userMessage);
        StringBuilder raw = new StringBuilder();
        return chatClient.prompt()
                .system(multiFilePrompt)
                .messages(hist)
                .user(format + "\n\n【请根据以下用户需求生成网站：】\n" + userMessage)
                .stream()
                .content()
                .doOnNext(token -> {
                    if (raw.length() < MAX_MEMORY_TEXT) {
                        raw.append(token);
                    }
                })
                .doOnComplete(() -> addAssistantMessage(appId, raw.toString()))
                .doOnError(e -> {
                    // 同上
                });
    }

    /**
     * 读取指定应用的历史对话（窗口内消息）
     */
    private List<Message> history(Long appId) {
        return chatMemory.get(String.valueOf(appId));
    }

    /**
     * 非流式：调用完成后一次写入用户消息与 AI 回复
     */
    private void remember(Long appId, String userMessage, String aiContent) {
        try {
            chatMemory.add(String.valueOf(appId),
                    List.of(new UserMessage(userMessage), new AssistantMessage(aiContent)));
        } catch (RuntimeException e) {
            // 记忆写入失败不影响生成主流程
        }
    }

    /**
     * 流式：调用前写入用户消息
     */
    private void addUserMessage(Long appId, String userMessage) {
        try {
            chatMemory.add(String.valueOf(appId), List.of(new UserMessage(userMessage)));
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    /**
     * 流式：完成后写入 AI 回复
     */
    private void addAssistantMessage(Long appId, String aiContent) {
        try {
            if (StrUtil.isBlank(aiContent)) {
                return;
            }
            chatMemory.add(String.valueOf(appId), List.of(new AssistantMessage(aiContent)));
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    /**
     * 校验用户需求不为空
     */
    private void checkUserMessage(String userMessage) {
        ThrowUtils.throwIf(StrUtil.isBlank(userMessage),
                ErrorCode.PARAMS_ERROR, "用户需求不能为空");
    }
}
