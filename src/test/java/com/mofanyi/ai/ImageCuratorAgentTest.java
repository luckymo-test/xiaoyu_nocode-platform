package com.mofanyi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.ai.model.ModelUsage;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.AiCallListener;
import com.mofanyi.core.SiteImageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Set;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class ImageCuratorAgentTest {

    private final ImageCuratorAgent agent = new ImageCuratorAgent(
            mock(SiteImageService.class), mock(ModelGateway.class),
            mock(AiCallListener.class), new ObjectMapper());

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

    @Test
    @DisplayName("视觉选片成功时写入统一 AI 调用审计")
    void visualCurateWritesUnifiedCallAudit() {
        ModelGateway gateway = mock(ModelGateway.class);
        AiCallListener listener = mock(AiCallListener.class);
        when(gateway.chat(any())).thenReturn(new ModelResponse(
                """
                        {"needed":true,"suggestedCount":1,"selected":[
                          {"id":1,"score":9,"purpose":"hero","description":"cosplay 人物","alt":"cosplay 主视觉"}
                        ],"rejected":[]}
                        """,
                "", List.of(), "stop", new ModelUsage(30, 10),
                "deepseek", "deepseek-flash", "deepseek-flash"));
        ImageCuratorAgent gatewayAgent = new ImageCuratorAgent(
                mock(SiteImageService.class), gateway, listener, new ObjectMapper());
        ImageCuratorAgent.Candidate candidate = new ImageCuratorAgent.Candidate(
                1, "https://example.com/cosplay.jpg", 1000, 1500,
                "cosplay portrait photography", "pexels");
        ImageCuratorAgent.VisualCandidate visual = new ImageCuratorAgent.VisualCandidate(
                candidate, 800, 1200, new byte[]{1, 2, 3});
        AiCallContext context = AiCallContext.ofJob(7L, 3L, "html", 99L);

        ReflectionTestUtils.invokeMethod(gatewayAgent, "evaluateBatch",
                "cosplay 照片墙", List.of(visual), false, context, 7L);

        verify(listener).onSuccess(eq(context), eq("stop"), eq(30L), eq(10L),
                anyLong(), eq("standard"), eq("deepseek"),
                eq("deepseek-flash"), eq("deepseek-flash"),
                eq(ModelTask.VISION_CURATE.name()));
    }

}
