package com.mofanyi.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实查询计划探针，默认跳过：
 * {@code mvn -o test -Dtest=ImageSearchPlanProbeTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ImageSearchPlanProbeTest {

    @Autowired
    private ImageMaterialPlanner planner;

    @Test
    @DisplayName("探针：六个明确主题必须生成独立检索 query")
    void plansOneQueryForEachExplicitTheme() {
        String requirement = "我想要一个漂亮的展示摄影作品的网页，要求要有真实的图片，"
                + "网页像照片墙一样可以展示不同的主题，包括自然风景，城市风景，人文风俗，"
                + "动物世界，cosplay，电影等几个主题。";

        List<String> queries = planner.planQueriesWithModel(requirement);
        String joined = String.join(" ", queries).toLowerCase(Locale.ROOT);
        System.out.println("[search-plan-probe] " + queries);

        assertTrue(queries.size() >= 6, "六个明确主题不应被合并成少量通用 query");
        assertTrue(joined.contains("landscape") || joined.contains("nature"));
        assertTrue(joined.contains("city") || joined.contains("urban"));
        assertTrue(joined.contains("culture") || joined.contains("ethnic")
                || joined.contains("folk") || joined.contains("tradition"));
        assertTrue(joined.contains("animal") || joined.contains("wildlife"));
        assertTrue(joined.contains("cosplay"));
        assertTrue(joined.contains("cinema") || joined.contains("cinematic")
                || joined.contains("film") || joined.contains("movie"));
        assertEquals(List.of("自然风景", "城市风景", "人文风俗", "动物世界", "cosplay", "电影"),
                planner.extractExplicitTopics(requirement));
    }

    @Test
    @DisplayName("探针：多主题页面最终素材必须覆盖 cosplay 等明确主题")
    void selectedMaterialsCoverExplicitThemes() {
        String requirement = "我想要一个漂亮的展示摄影作品的网页，要求要有真实的图片，"
                + "网页像照片墙一样可以展示不同的主题，包括自然风景，城市风景，人文风俗，"
                + "动物世界，cosplay，电影等几个主题。";

        List<ImageMaterialPlanner.Material> materials = planner.plan(
                com.mofanyi.model.enums.CodeGenTypeEnum.HTML,
                990001000000000002L, requirement);
        Set<String> queries = materials.stream()
                .map(ImageMaterialPlanner.Material::query)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        System.out.println("[multi-topic-material-probe] materials=" + materials.size()
                + " queries=" + queries);

        assertFalse(materials.isEmpty(), "多主题页面应选出可用素材");
        assertTrue(queries.stream().anyMatch(query -> query.toLowerCase(Locale.ROOT).contains("cosplay")),
                "最终素材必须包含 Cosplay 主题，不能只取到通用摄影图");
    }
}
