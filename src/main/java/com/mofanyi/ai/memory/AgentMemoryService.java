package com.mofanyi.ai.memory;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.ChatHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
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
@Conditional(GenerationExecutionCondition.class)
public class AgentMemoryService {

    /**
     * 注入模型的上下文 token 预算（估算值，非精确计数）。
     * 超过则从最旧的消息开始丢弃，宁可丢旧、不可爆上下文。
     */
    private static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 6000;

    /**
     * 单条记忆最大字符数：个别超长消息（如异常堆栈）先截断，避免单条挤爆窗口。
     */
    private static final int MAX_MESSAGE_CHARS = 2000;

    /**
     * 冷启动回灌时最多读取的历史条数。
     */
    private static final int REHYDRATE_LIMIT = 30;

    /**
     * 重新补回"最初需求"时的字符上限（避免一条超长需求反客为主）
     */
    private static final int FIRST_REQUIREMENT_MAX_CHARS = 500;

    private final ChatMemory chatMemory;
    private final ChatHistoryService chatHistoryService;
    private final ContextCompressionService contextCompressionService;

    @Value("${codegen.memory.context-token-budget:16000}")
    private int contextTokenBudget = 16000;

    @Autowired
    public AgentMemoryService(ChatMemory chatMemory,
                              ChatHistoryService chatHistoryService,
                              ContextCompressionService contextCompressionService) {
        this.chatMemory = chatMemory;
        this.chatHistoryService = chatHistoryService;
        this.contextCompressionService = contextCompressionService;
    }

    /**
     * 兼容无上下文压缩器的旧调用/单元测试。
     */
    public AgentMemoryService(ChatMemory chatMemory, ChatHistoryService chatHistoryService) {
        this(chatMemory, chatHistoryService, null);
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
        List<Message> messages = loadRawContext(appId, currentUserText);
        return trimToTokenBudget(messages, DEFAULT_CONTEXT_TOKEN_BUDGET);
    }

    /**
     * 生成链路使用：先消除规格摘要与历史记忆的重复，再对较早对话做可选语义压缩。
     */
    public List<Message> loadContext(CodeGenTypeEnum type, long appId, String currentUserText,
                                     String specSection) {
        return loadContext(type, appId, currentUserText, specSection, null);
    }

    public List<Message> loadContext(CodeGenTypeEnum type, long appId, String currentUserText,
                                     String specSection, AiCallContext callContext) {
        List<Message> messages = loadRawContext(appId, currentUserText);
        messages = dropStateDuplicatedBySpec(messages, specSection);
        if (contextCompressionService != null) {
            messages = contextCompressionService.compressIfNeeded(
                    type, appId, messages, callContext);
        }
        return trimToTokenBudget(messages, contextTokenBudget);
    }

    private List<Message> loadRawContext(long appId, String currentUserText) {
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
        return new ArrayList<>(messages);
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
     * 上下文压缩 + 选择性保留（M3）：
     * <ol>
     *   <li>按 token 预算从最旧的消息开始裁剪（保留最近的），至少保留最后一条；</li>
     *   <li><b>选择性保留</b>：无论怎么裁，"最初的需求"那一条用户消息都要补回并置于最前——
     *       它承载应用的原始目标，硬切窗口最容易把它丢掉；</li>
     *   <li><b>压缩</b>：被省略的中段不再注入（附一条省略提示）。应用当前状态由规格摘要
     *       （{@link AppSpecService}）承载，近期对话只负责"上文指代"，因此无需额外调用模型做摘要，成本为零。</li>
     * </ol>
     */
    private List<Message> trimToTokenBudget(List<Message> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        LinkedList<Message> kept = new LinkedList<>();
        int total = 0;
        ListIterator<Message> it = messages.listIterator(messages.size());
        while (it.hasPrevious()) {
            Message m = it.previous();
            int tokens = estimateTokens(m.getText());
            if (!kept.isEmpty() && total + tokens > tokenBudget) {
                break;
            }
            total += tokens;
            kept.addFirst(m);
        }
        int omitted = messages.size() - kept.size();
        // 选择性保留原始需求：被裁掉时补回最前，并标注中段已省略
        Message firstUser = firstUserMessage(messages);
        if (firstUser != null && !containsByReference(kept, firstUser)) {
            kept.addFirst(new UserMessage(buildFirstRequirementNotice(firstUser.getText(), omitted)));
        }
        return new ArrayList<>(kept);
    }

    /**
     * 应用现状是状态的唯一来源：旧版记忆中的生成摘要、文件索引和初始需求不再重复注入。
     */
    static List<Message> dropStateDuplicatedBySpec(List<Message> messages, String specSection) {
        if (messages == null || messages.isEmpty() || StrUtil.isBlank(specSection)) {
            return messages == null ? List.of() : messages;
        }
        String normalizedSpec = oneLine(specSection);
        String initialRequirement = extractInitialRequirement(specSection);
        List<Message> filtered = new ArrayList<>(messages.size());
        boolean initialRemoved = false;
        for (Message message : messages) {
            String text = message.getText();
            if (!initialRemoved && message instanceof UserMessage
                    && StrUtil.isNotBlank(initialRequirement)
                    && oneLine(text).equals(oneLine(initialRequirement))) {
                initialRemoved = true;
                continue;
            }
            if (message instanceof AssistantMessage && StrUtil.isNotBlank(text)) {
                String normalized = oneLine(text);
                if ((normalized.startsWith("已完成") && normalized.contains("生成"))
                        || normalizedSpec.contains(normalized)) {
                    continue;
                }
            }
            filtered.add(message);
        }
        return filtered;
    }

    private static String extractInitialRequirement(String specSection) {
        for (String line : specSection.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("初始需求：")) {
                return trimmed.substring("初始需求：".length()).trim();
            }
        }
        return "";
    }

    /**
     * 取第一条用户消息（即最初的需求）
     */
    private static Message firstUserMessage(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof UserMessage) {
                return message;
            }
        }
        return null;
    }

    /**
     * 按引用判断是否已保留（Spring AI 的 Message 未保证 equals 语义）
     */
    private static boolean containsByReference(List<Message> messages, Message target) {
        for (Message message : messages) {
            if (message == target) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造补回的最初需求：截断 + 标注中段省略
     */
    private static String buildFirstRequirementNotice(String firstRequirement, int omitted) {
        String text = firstRequirement == null ? "" : firstRequirement;
        if (text.length() > FIRST_REQUIREMENT_MAX_CHARS) {
            text = text.substring(0, FIRST_REQUIREMENT_MAX_CHARS) + "…(已截断)";
        }
        return "【最初的需求（请始终遵守）】" + text
                + "\n（注：中间已省略 " + Math.max(omitted, 0) + " 条较早对话；应用当前状态见上方规格摘要）";
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

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
