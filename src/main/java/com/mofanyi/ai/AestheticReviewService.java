package com.mofanyi.ai;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelCapability;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.RenderVerifier;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用三档截图和 DOM 事实做审美评审。评审只产出结构化问题和修复建议，不直接改代码。
 */
@Slf4j
@Component
public class AestheticReviewService {

    private static final long MAX_SCREENSHOT_BYTES = 4L * 1024 * 1024;

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;
    private final CodeFileWriteService files;
    private final String systemPrompt;

    @Value("${codegen.aesthetic-review.enabled:true}")
    private boolean enabled;

    public AestheticReviewService(ModelGateway modelGateway,
                                  ObjectMapper objectMapper,
                                  CodeFileWriteService files) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
        this.files = files;
        try {
            this.systemPrompt = new ClassPathResource("prompt/aesthetic_review_prompt.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载审美评审 Prompt 失败", e);
        }
    }

    public Review review(CodeGenTypeEnum type, Long appId, RenderVerifier.RenderReport report,
                         String stage) {
        if (!enabled || report == null || !report.isUsable()) {
            return Review.skipped();
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(buildDomContext(type, appId, report)));
        for (RenderVerifier.ViewportResult viewport : report.viewports()) {
            addScreenshot(messages, viewport);
        }
        messages.add(new UserMessage("请结合截图和 DOM 事实输出审美评审 JSON。"));
        try {
            ModelResponse response = modelGateway.chat(new ModelRequest(
                    ModelTask.VISION_CURATE,
                    messages,
                    List.of(),
                    Set.of(ModelCapability.VISION),
                    0.2,
                    1_500,
                    false,
                    null,
                    false,
                    null,
                    null));
            Review review = Review.parse(objectMapper, response.text());
            persist(type, appId, stage, review, response);
            log.info("[aesthetic] appId={} stage={} score={} verdict={} issues={}",
                    appId, stage, review.score(), review.verdict(), review.issues().size());
            return review;
        } catch (Exception e) {
            log.warn("[aesthetic] 审美评审失败，跳过而不影响发布，appId={}", appId, e);
            return Review.skipped();
        }
    }

    private String buildDomContext(CodeGenTypeEnum type, Long appId,
                                   RenderVerifier.RenderReport report) {
        StringBuilder text = new StringBuilder("DOM/响应式事实：\n");
        for (RenderVerifier.ViewportResult viewport : report.viewports()) {
            text.append("- ").append(viewport.name())
                    .append(" width=").append(viewport.width())
                    .append(" overflow=").append(viewport.overflow())
                    .append(" textLength=").append(viewport.textLength())
                    .append(" brokenImages=").append(viewport.brokenImages().size())
                    .append(" consoleErrors=").append(viewport.consoleErrors().size())
                    .append('\n');
        }
        text.append("源码文件索引：\n");
        Path root = Path.of(files.getProjectDir(type, appId));
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().contains("node_modules"))
                    .filter(path -> !path.toString().contains(".agent"))
                    .limit(200)
                    .forEach(path -> text.append("- ")
                            .append(root.relativize(path).toString().replace('\\', '/'))
                            .append('\n'));
        } catch (Exception e) {
            text.append("（源码索引不可用）\n");
        }
        return text.toString();
    }

    private void addScreenshot(List<Message> messages, RenderVerifier.ViewportResult viewport) {
        if (viewport == null || viewport.screenshotPath() == null) {
            return;
        }
        File image = new File(viewport.screenshotPath());
        if (!image.isFile() || image.length() > MAX_SCREENSHOT_BYTES) {
            return;
        }
        try {
            byte[] bytes = FileUtil.readBytes(image);
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(new ByteArrayResource(bytes))
                    .build();
            messages.add(UserMessage.builder()
                    .text("视口=" + viewport.name() + "，宽度=" + viewport.width())
                    .media(media)
                    .build());
        } catch (Exception e) {
            log.debug("[aesthetic] 截图读取失败：{}", image, e);
        }
    }

    private void persist(CodeGenTypeEnum type, Long appId, String stage, Review review,
                         ModelResponse response) {
        var root = objectMapper.createObjectNode();
        root.put("stage", stage);
        root.put("score", review.score());
        root.put("verdict", review.verdict());
        root.put("signature", review.signature());
        root.set("issues", objectMapper.valueToTree(review.issues()));
        root.put("provider", response.provider());
        root.put("model", response.modelName());
        root.put("promptTokens", response.usage().promptTokens());
        root.put("completionTokens", response.usage().completionTokens());
        Path out = Path.of(files.getProjectDir(type, appId),
                ".agent", "aesthetic-review-" + stage + ".json");
        try {
            Files.createDirectories(out.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
        } catch (Exception e) {
            log.warn("[aesthetic] 评审报告落盘失败，appId={}", appId, e);
        }
    }

    public record Issue(String section, String problem, String fix) {
    }

    public record Review(int score, String verdict, String signature, List<Issue> issues) {

        static Review skipped() {
            return new Review(0, "skipped", "", List.of());
        }

        static Review parse(ObjectMapper mapper, String raw) throws Exception {
            String text = raw == null ? "" : raw.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("审美评审未返回 JSON");
            }
            JsonNode root = mapper.readTree(text.substring(start, end + 1));
            List<Issue> issues = new ArrayList<>();
            for (JsonNode node : root.path("issues")) {
                if (issues.size() >= 10) {
                    break;
                }
                issues.add(new Issue(
                        node.path("section").asText("").substring(0,
                                Math.min(80, node.path("section").asText("").length())),
                        node.path("problem").asText("").substring(0,
                                Math.min(500, node.path("problem").asText("").length())),
                        node.path("fix").asText("").substring(0,
                                Math.min(500, node.path("fix").asText("").length()))));
            }
            return new Review(
                    Math.max(0, Math.min(10, root.path("score").asInt(0))),
                    root.path("verdict").asText("usable"),
                    root.path("signature").asText(""),
                    List.copyOf(issues));
        }
    }
}
