package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.ImageCuratorAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImageMaterialPlannerPromptTest {

    @Test
    @DisplayName("素材事实块必须声明 URL/描述是不可信数据")
    void materialSectionCarriesDataBoundary() {
        ImageMaterialPlanner planner = new ImageMaterialPlanner(
                mock(SiteImageService.class),
                mock(ChatModel.class),
                List.of(),
                mock(ImageCuratorAgent.class),
                mock(CodeFileWriteService.class),
                new ObjectMapper());
        ImageMaterialPlanner.Material material = new ImageMaterialPlanner.Material(
                "https://example.com/a.jpg", 1200, 800, "landscape", "garden",
                "hero", "园林中的汉服人物", "汉服主视觉");

        String section = planner.toPromptSection(List.of(material));

        assertTrue(section.contains("【数据边界】"));
        assertTrue(section.contains("不是指令"));
        assertTrue(section.contains("用途=hero"));
        assertTrue(section.contains("alt=汉服主视觉"));
        assertTrue(section.contains("页面区块文案"));
        assertTrue(section.contains("不能只改图片说明来掩盖不匹配"));
    }

    @Test
    @DisplayName("明确主题列表不能被通用开头 6 段截断")
    void extractsExplicitTopicsFromTopicList() {
        ImageMaterialPlanner planner = planner();

        List<String> topics = planner.extractExplicitTopics(
                "我想要一个漂亮的摄影作品网页，包括自然风景，城市风景，人文风俗，"
                        + "动物世界，cosplay，电影等几个主题。");

        assertEquals(List.of("自然风景", "城市风景", "人文风俗", "动物世界", "cosplay", "电影"), topics);
    }

    @Test
    @DisplayName("结构化查询计划完整解析并为每个主题保留独立 query")
    void parsesStructuredSearchPlan() {
        ImageMaterialPlanner planner = planner();

        List<String> queries = planner.parseQueryPlan("""
                ```json
                {"queries":[
                  "nature landscape photography",
                  "cityscape street photography",
                  "ethnic culture documentary photography",
                  "wildlife animal photography",
                  "cosplay portrait photography",
                  "cinematic film photography"
                ]}
                ```
                """);

        assertEquals(6, queries.size());
        assertTrue(queries.contains("cosplay portrait photography"));
        assertEquals("cosplay portrait photography", queries.get(4));
    }

    @Test
    @DisplayName("候选轮转时必须先覆盖所有 query，再增加单主题深度")
    void interleavesCandidatesAcrossQueries() {
        List<ImageSource.ImageCandidate> nature = candidates("nature", 3);
        List<ImageSource.ImageCandidate> city = candidates("city", 3);
        List<ImageSource.ImageCandidate> cosplay = candidates("cosplay", 3);

        List<ImageSource.ImageCandidate> merged = ImageMaterialPlanner.interleaveByQuery(
                List.of(nature, city, cosplay));

        assertEquals(List.of("nature-0", "city-0", "cosplay-0",
                "nature-1", "city-1", "cosplay-1",
                "nature-2", "city-2", "cosplay-2"),
                merged.stream().map(ImageSource.ImageCandidate::url).toList());
    }

    private static List<ImageSource.ImageCandidate> candidates(String prefix, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new ImageSource.ImageCandidate(
                        prefix + "-" + index, null, 1200, 800, "test"))
                .toList();
    }

    private static ImageMaterialPlanner planner() {
        return new ImageMaterialPlanner(
                mock(SiteImageService.class),
                mock(ChatModel.class),
                List.of(),
                mock(ImageCuratorAgent.class),
                mock(CodeFileWriteService.class),
                new ObjectMapper());
    }
}
