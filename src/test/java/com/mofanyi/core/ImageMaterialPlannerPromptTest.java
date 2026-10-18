package com.mofanyi.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.ImageCuratorAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

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
    }
}
