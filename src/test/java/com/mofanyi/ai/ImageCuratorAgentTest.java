package com.mofanyi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.core.SiteImageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Set;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImageCuratorAgentTest {

    private final ImageCuratorAgent agent = new ImageCuratorAgent(
            mock(SiteImageService.class), mock(ModelGateway.class), new ObjectMapper());

    @Test
    @DisplayName("严格过滤越界 id、重复选片和非法用途")
    void validatesCuratorJson() {
        String raw = """
                ```json
                {
                  "needed": true,
                  "suggestedCount": 2,
                  "selected": [
                    {"id":1,"score":12,"purpose":"hero","description":"园林中的汉服人物","alt":"汉服主视觉"},
                    {"id":1,"score":9,"purpose":"card","description":"重复","alt":"重复"},
                    {"id":99,"score":9,"purpose":"card","description":"越界","alt":"越界"},
                    {"id":2,"score":7,"purpose":"unknown","description":"侧脸特写","alt":"人物特写"}
                  ],
                  "rejected": [
                    {"id":1,"reason":"已选中，不应再拒绝"},
                    {"id":3,"reason":"与需求无关"}
                  ]
                }
                ```
                """;

        ImageCuratorAgent.ParsedBatch parsed = agent.parseBatch(raw, Set.of(1, 2, 3));

        assertEquals(Set.of(1, 2), parsed.selectedIds());
        assertEquals(2, parsed.selections().size());
        assertEquals(10, parsed.selections().get(0).score());
        assertEquals("hero", parsed.selections().get(0).purpose());
        assertEquals("card", parsed.selections().get(1).purpose());
        assertEquals(1, parsed.rejections().size());
        assertEquals(3, parsed.rejections().get(0).id());
    }

    @Test
    @DisplayName("非 JSON 返回立即判为非法，等待调用层重试/降级")
    void rejectsNonJsonResponse() {
        assertThrows(IllegalArgumentException.class,
                () -> agent.parseBatch("我认为这些图都不错", Set.of(1)));
    }

    @Test
    @DisplayName("空数组表示整批不选，而非非法 JSON")
    void acceptsEmptyBatchAsAllRejected() {
        String raw = """
                {"needed":false,"suggestedCount":0,"selected":[],"rejected":[]}
                """;

        ImageCuratorAgent.ParsedBatch parsed = agent.parseBatch(raw, Set.of(3, 7));

        assertEquals(Set.of(), parsed.selectedIds());
        assertEquals(2, parsed.rejections().size());
    }

    @Test
    @DisplayName("needed=true 的空批次同样解释为本批无入选")
    void acceptsNeededButEmptyBatch() {
        String raw = """
                {"needed":true,"suggestedCount":6,"selected":[],"rejected":[]}
                """;

        ImageCuratorAgent.ParsedBatch parsed = agent.parseBatch(raw, Set.of(1, 2));

        assertEquals(Set.of(), parsed.selectedIds());
        assertEquals(2, parsed.rejections().size());
    }

    @Test
    @DisplayName("needed 与 selected 冲突时以实际 selected 为准")
    void selectedWinsOverInconsistentNeededFlag() {
        String raw = """
                {
                  "needed":false,
                  "suggestedCount":1,
                  "selected":[
                    {"id":2,"score":8,"purpose":"hero","description":"人物主视觉","alt":"人物主视觉"}
                  ],
                  "rejected":[]
                }
                """;

        ImageCuratorAgent.ParsedBatch parsed = agent.parseBatch(raw, Set.of(2));

        assertEquals(Set.of(2), parsed.selectedIds());
    }

    @Test
    @DisplayName("每张候选图前必须携带同一 id 的需求相关事实")
    @SuppressWarnings("unchecked")
    void visualContentBindsFactsToEachImage() {
        String requirement = "摄影工作室官网，主视觉必须是穿汉服的人，作品区展示园林环境";
        ImageCuratorAgent.Candidate first = new ImageCuratorAgent.Candidate(
                17, "https://example.com/hanfu.jpg", 1200, 1800,
                "hanfu garden", "pexels");
        ImageCuratorAgent.VisualCandidate visual = new ImageCuratorAgent.VisualCandidate(
                first, 1024, 1536, new byte[]{1, 2, 3});

        List<Map<String, Object>> content = agent.buildVisualContent(
                requirement, List.of(visual), false);

        assertEquals(4, content.size());
        String header = (String) content.get(0).get("text");
        assertTrue(header.contains(requirement));
        assertTrue(header.contains("语义依据"));
        assertTrue(header.contains("禁止按图片出现顺序猜 id"));

        String candidateFacts = (String) content.get(1).get("text");
        assertTrue(candidateFacts.contains("id=17"));
        assertTrue(candidateFacts.contains("1024x1536"));
        assertTrue(candidateFacts.contains("hanfu garden"));
        assertTrue(candidateFacts.contains("pexels"));

        Map<String, Object> image = (Map<String, Object>) content.get(2).get("image_url");
        assertTrue(((String) image.get("url")).startsWith("data:image/jpeg;base64,"));
        assertEquals("low", image.get("detail"));
    }

    @Test
    @DisplayName("Gateway 多模态请求把每个候选事实与对应图片放在同一消息")
    void gatewayMessagesBindFactsToMedia() {
        ImageCuratorAgent.Candidate candidate = new ImageCuratorAgent.Candidate(
                23, "https://example.com/cosplay.jpg", 1000, 1500,
                "cosplay portrait photography", "pexels");
        ImageCuratorAgent.VisualCandidate visual = new ImageCuratorAgent.VisualCandidate(
                candidate, 800, 1200, new byte[]{1, 2, 3});

        List<Message> messages = agent.buildVisualMessages(
                "摄影照片墙，必须有 cosplay 主题", List.of(visual), true);

        assertEquals(4, messages.size());
        assertTrue(messages.get(0).getText().contains("选片"));
        assertTrue(messages.get(1).getText().contains("cosplay"));
        UserMessage candidateMessage = (UserMessage) messages.get(2);
        assertTrue(candidateMessage.getText().contains("id=23"));
        assertTrue(candidateMessage.getText().contains("cosplay portrait photography"));
        assertEquals(1, candidateMessage.getMedia().size());
        assertEquals(3, candidateMessage.getMedia().getFirst().getDataAsByteArray().length);
    }

}
