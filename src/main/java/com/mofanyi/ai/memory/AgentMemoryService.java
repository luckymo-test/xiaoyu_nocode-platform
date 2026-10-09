package com.mofanyi.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * Agent 对话记忆的读写门面：Redis 热窗口 + MySQL 冷回灌。
 *
 * <p>职责边界（对应 plan-agent-engineering.md 第二章 M0/M1）：
 * <ul>
 *   <li><b>读</b>：{@link #loadContext(long)} 优先取 Redis 窗口；miss（首次 / 过期 / 重启）时从
 *       {@code chat_history} 回灌最近若干条，并写回 Redis 作热缓存。</li>
 *   <li><b>写</b>：{@link #appendTurn(long, String, String)} 记录一轮对话。写入内容<b>去代码化</b>
 *       ——只存说明与文件索引，不存全量代码（代码在磁盘上，模型需要时用 readFile 取）。</li>
 *   <li><b>裁剪</b>：按<b>估算 token</b>预算裁剪，而非按消息条数。</li>
 * </ul>
 *
 * <p>设计原则：MySQL 是真相源，Redis 只是热缓存——缓存丢了可以重建，不会"失忆"。
 */
@Slf4j
@Service
public class AgentMemoryService {

    /**
     * 注入模型的上下文 token 预算（估算值，非精确计数）。
     * 超过则从最旧的消息开始丢弃，宁可丢旧、不可爆上下文。
     */
    private static final int CONTEXT_TOKEN_BUDGET = 6000;

    /**
     * 单条记忆最大字符数：个别超长消息（如异常堆栈）先截断，避免单条挤爆窗口。
     */
    private static final int MAX_MESSAGE_CHARS = 2000;

    /**
     * 冷启动回灌时最多读取的历史条数。
     */
    private static final int REHYDRATE_LIMIT = 30;

    private final ChatMemory chatMemory;
    private final ChatHistoryService chatHistoryService;

    public AgentMemoryService(ChatMemory chatMemory, ChatHistoryService chatHistoryService) {
        this.chatMemory = chatMemory;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 读取某应用的上下文消息：优先 Redis 热窗口，miss 时从 MySQL 回灌。
     *
     * @param appId           应用 id
     * @param currentUserText 本轮用户输入。用于去重——controller 在生成前已把本轮用户消息落库，
     *                        回灌会把它读进来；这里会丢掉尾部这条，避免调用方再 add 一次造成重复。
     * @return 按时间正序、已按 token 预算裁剪的消息列表（可能为空）
     */
    public List<Message> loadContext(long appId, String currentUserText) {
        List<Message> messages = safeGet(appId);
        if (messages.isEmpty()) {
            messages = rehydrate(appId, currentUserText);
            if (!messages.isEmpty()) {
                // 回灌结果写回热缓存，下次直接命中；写失败不影响本次返回
                try {
                    chatMemory.add(String.valueOf(appId), messages);
                } catch (RuntimeException e) {
                    log.warn("[memory] 回灌写回缓存失败，appId={}", appId, e);
                }
            }
        }
        return trimToTokenBudget(messages);
    }

    /**
     * 记录一轮对话（用户消息 + AI 摘要）。写入内容应已去代码化。
     *
     * @param appId         应用 id
     * @param userText      用户消息
     * @param assistantText AI 回复摘要（说明 + 文件索引，不含全量代码）
     */
    public void appendTurn(long appId, String userText, String assistantText) {
        List<Message> turn = new ArrayList<>(2);
        if (StrUtil.isNotBlank(userText)) {
            turn.add(new UserMessage(clip(userText)));
        }
        if (StrUtil.isNotBlank(assistantText)) {
            turn.add(new AssistantMessage(clip(assistantText)));
        }
        if (turn.isEmpty()) {
            return;
        }
        try {
            chatMemory.add(String.valueOf(appId), turn);
        } catch (RuntimeException e) {
            // 记忆写入失败不影响生成主流程
            log.warn("[memory] 写入对话记忆失败，appId={}", appId, e);
        }
    }

    /**
     * 从读的热窗口；异常时降级为空（视为 miss，走回灌）
     */
    private List<Message> safeGet(long appId) {
        try {
            List<Message> messages = chatMemory.get(String.valueOf(appId));
            return messages == null ? List.of() : messages;
        } catch (RuntimeException e) {
            log.warn("[memory] 读取对话记忆失败，降级为回灌，appId={}", appId, e);
            return List.of();
        }
    }

    /**
     * 从 MySQL chat_history 回灌最近若干条消息。
     * 其中 AI 回复剥离代码块——历史里的代码是展示格式，回灌进模型上下文纯属浪费（代码在磁盘上）。
     * 错误消息只作前端展示，不回灌。
     */
    private List<Message> rehydrate(long appId, String currentUserText) {
        List<ChatHistory> history;
        try {
            history = chatHistoryService.listRecentByAppId(appId, REHYDRATE_LIMIT);
        } catch (RuntimeException e) {
            log.warn("[memory] 回灌读取历史失败，appId={}", appId, e);
            return List.of();
        }
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> out = new ArrayList<>(history.size());
        for (ChatHistory h : history) {
            ChatMessageTypeEnum type = ChatMessageTypeEnum.getEnumByValue(h.getMessageType());
            if (type == null) {
                continue;
            }
            String text = h.getMessage() == null ? "" : h.getMessage();
            switch (type) {
                case USER -> out.add(new UserMessage(clip(text)));
                case ASSISTANT -> out.add(new AssistantMessage(clip(stripCodeBlocks(text))));
                // 错误消息是给用户看的，不进模型上下文
                case ERROR -> {
                }
            }
        }
        dropTrailingCurrentUserMessage(out, currentUserText);
        if (!out.isEmpty()) {
            log.info("[memory] 冷启动回灌 {} 条历史，appId={}", out.size(), appId);
        }
        return out;
    }

    /**
     * 丢掉回灌结果尾部的"本轮用户消息"（controller 已提前落库），避免与调用方随后追加的输入重复。
     */
    private static void dropTrailingCurrentUserMessage(List<Message> messages, String currentUserText) {
        if (messages.isEmpty() || StrUtil.isBlank(currentUserText)) {
            return;
        }
        Message last = messages.get(messages.size() - 1);
        if (last instanceof UserMessage && clip(currentUserText).equals(last.getText())) {
            messages.remove(messages.size() - 1);
        }
    }

    /**
     * 按 token 预算从最旧的消息开始裁剪（保留最近的）。
     * 至少保留最后一条，避免全部丢弃导致"完全无上下文"。
     */
    private List<Message> trimToTokenBudget(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        LinkedList<Message> kept = new LinkedList<>();
        int total = 0;
        ListIterator<Message> it = messages.listIterator(messages.size());
        while (it.hasPrevious()) {
            Message m = it.previous();
            int tokens = estimateTokens(m.getText());
            if (!kept.isEmpty() && total + tokens > CONTEXT_TOKEN_BUDGET) {
                break;
            }
            total += tokens;
            kept.addFirst(m);
        }
        return new ArrayList<>(kept);
    }

    /**
     * 粗略估算 token 数：中日韩字符约 1 字 1 token，其余字符约 4 字符 1 token。
     * 只用于预算裁剪，不追求精确。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    /**
     * 剥离 Markdown 代码块（```lang ... ```），替换为简短标记。
     * 历史 AI 回复是带全量代码的展示格式，回灌时必须去掉代码，否则等于把代码又塞回上下文。
     */
    static String stripCodeBlocks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("(?s)```.*?```", "[代码已省略，见工程文件]");
    }

    /**
     * 截断超长文本（单条记忆上限）
     */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_CHARS) + "…(已截断)";
    }
}
