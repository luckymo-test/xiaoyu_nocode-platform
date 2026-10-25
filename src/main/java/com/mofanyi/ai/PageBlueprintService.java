package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.TokenUsageAccumulator;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 编码前的页面蓝图层：把视觉方向、版块节奏和图片用途固定为事实，降低模型直接进入
 * “堆卡片”模式。规划失败时返回空蓝图，由通用视觉 Prompt 兜底，不阻塞生成。
 */
@Slf4j
@Component
public class PageBlueprintService {

    private static final int MAX_BLUEPRINT_CHARS = 12_000;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    @Value("${codegen.blueprint.enabled:true}")
    private boolean enabled;

    public PageBlueprintService(ModelGateway modelGateway, ObjectMapper objectMapper) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        try {
            this.systemPrompt = new ClassPathResource("prompt/page_blueprint_prompt.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载页面蓝图 Prompt 失败", e);
        }
    }

    public Plan plan(CodeGenTypeEnum type,
                     Long appId,
                     String userMessage,
                     String templateSection,
                     String materialSection,
                     AiCallContext context) {
        if (!enabled) {
            return Plan.empty();
        }
        String planningInput = "生成类型：" + type.getValue()
                + "\n用户需求：" + userMessage
                + "\n模板视觉系统：\n" + StrUtil.blankToDefault(templateSection, "无")
                + "\n素材清单：\n" + StrUtil.blankToDefault(materialSection, "无");
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(planningInput));
        try {
            ModelResponse response = modelGateway.chat(new ModelRequest(
                    ModelTask.QUERY_PLAN,
                    messages,
                    List.of(),
                    java.util.Set.of(),
                    0.2,
                    1_200,
                    false,
                    null,
                    false,
                    null,
                    context));
            JsonNode json = normalizeJson(response.text());
            String section = "【页面蓝图（编码必须遵循）】\n" + json
                    + "\n蓝图约束版块结构、视觉节奏和图片用途；"
                    + "实现时可优化细节，但不得退回同尺寸卡片堆砌。";
            log.info("[blueprint] appId={} sections={} direction={}",
                    appId, json.path("sections").size(),
                    StrUtil.maxLength(json.path("visualDirection").asText(""), 80));
            return new Plan(section, new TokenUsageAccumulator.TokenUsage(
                    response.usage().promptTokens(), response.usage().completionTokens()));
        } catch (Exception e) {
            log.warn("[blueprint] 页面蓝图生成失败，降级为通用视觉 Prompt，appId={}", appId, e);
            return Plan.empty();
        }
    }

    private JsonNode normalizeJson(String text) throws Exception {
        String value = StrUtil.blankToDefault(text, "").trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("蓝图未返回 JSON 对象");
        }
        value = value.substring(start, end + 1);
        if (value.length() > MAX_BLUEPRINT_CHARS) {
            throw new IllegalArgumentException("蓝图超过长度上限");
        }
        JsonNode root = objectMapper.readTree(value);
        if (!root.isObject() || !root.path("sections").isArray()
                || root.path("sections").isEmpty()) {
            throw new IllegalArgumentException("蓝图缺少 sections");
        }
        return root;
    }

    public record Plan(String promptSection, TokenUsageAccumulator.TokenUsage usage) {

        static Plan empty() {
            return new Plan("", new TokenUsageAccumulator.TokenUsage(0, 0));
        }
    }
}
