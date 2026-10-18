package com.mofanyi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.core.SiteImageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ImageCuratorAgentTest {

    private final ImageCuratorAgent agent = new ImageCuratorAgent(
            mock(SiteImageService.class), new ObjectMapper());

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
    @DisplayName("复核不足 6 张时只从 Pass 1 高分未被拒候选补选")
    void fillsShortfallFromHighScorePassOne() {
        Map<Integer, ImageCuratorAgent.Selection> finalSelections = new LinkedHashMap<>();
        finalSelections.put(1, new ImageCuratorAgent.Selection(
                1, 9, "hero", "主视觉", "主视觉"));
        Map<Integer, ImageCuratorAgent.Selection> passOne = new LinkedHashMap<>();
        passOne.put(1, finalSelections.get(1));
        passOne.put(2, new ImageCuratorAgent.Selection(2, 9, "portrait", "侧脸", "侧脸"));
        passOne.put(3, new ImageCuratorAgent.Selection(3, 8, "portrait", "背影", "背影"));
        passOne.put(4, new ImageCuratorAgent.Selection(4, 8, "gallery", "作品一", "作品一"));
        passOne.put(5, new ImageCuratorAgent.Selection(5, 7, "card", "卡片一", "卡片一"));
        passOne.put(6, new ImageCuratorAgent.Selection(6, 8, "card", "卡片二", "卡片二"));
        passOne.put(7, new ImageCuratorAgent.Selection(7, 7, "gallery", "作品二", "作品二"));
        passOne.put(8, new ImageCuratorAgent.Selection(8, 9, "card", "复核拒绝", "复核拒绝"));
        passOne.put(9, new ImageCuratorAgent.Selection(9, 6, "card", "低分", "低分"));
        Map<Integer, ImageCuratorAgent.Rejection> rejected = Map.of(
                8, new ImageCuratorAgent.Rejection(8, "水印明显"));

        int added = agent.fillFromPassOne(finalSelections, passOne, rejected,
                Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9));

        assertEquals(5, added);
        assertEquals(6, finalSelections.size());
        assertEquals(false, finalSelections.containsKey(8));
        assertEquals(false, finalSelections.containsKey(9));
    }

    @Test
    @DisplayName("用途配额控制同用途过度集中")
    void purposeLimitsAreBounded() {
        assertEquals(2, ImageCuratorAgent.maxForPurpose("hero"));
        assertEquals(3, ImageCuratorAgent.maxForPurpose("gallery"));
        assertEquals(2, ImageCuratorAgent.maxForPurpose("avatar"));
    }
}
