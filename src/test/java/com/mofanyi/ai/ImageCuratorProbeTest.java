package com.mofanyi.ai;

import com.mofanyi.core.ImageSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实图片选片探针。默认跳过，需图库与模型密钥：
 * {@code mvn -o test -Dtest=ImageCuratorProbeTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ImageCuratorProbeTest {

    @Autowired
    private ImageCuratorAgent imageCuratorAgent;

    @Autowired
    private List<ImageSource> imageSources;

    @Test
    @DisplayName("探针：真实候选图两轮视觉筛选")
    void curatesRealCandidates() {
        List<ImageCuratorAgent.Candidate> candidates = new ArrayList<>();
        for (ImageSource source : imageSources) {
            List<ImageSource.ImageCandidate> found = source.search("hanfu garden portrait", "portrait", 10);
            if (!found.isEmpty()) {
                int id = 0;
                for (ImageSource.ImageCandidate image : found) {
                    candidates.add(new ImageCuratorAgent.Candidate(
                            id++, image.url(), image.width(), image.height(),
                            "hanfu garden portrait", source.name()));
                }
                break;
            }
        }
        Assumptions.assumeFalse(candidates.isEmpty(), "图库密钥未配置或无候选图");

        long start = System.currentTimeMillis();
        ImageCuratorAgent.Result result = imageCuratorAgent.curate(
                1L, "现代汉服摄影工作室官网，需要人物主视觉、作品画廊和竖版卡片图，柔和自然光，东方审美。",
                candidates);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[curator-probe] candidates=" + candidates.size()
                + " selected=" + result.selected().size()
                + " rejected=" + result.rejected().size()
                + " elapsedMs=" + elapsed);
        result.selected().forEach(selection -> System.out.println("[curator-probe] " + selection));

        assertFalse(result.selected().isEmpty(), "视觉筛选应至少选出一张图");
        assertTrue(result.selected().stream().allMatch(selection ->
                candidates.stream().anyMatch(candidate -> candidate.id() == selection.id())));
    }
}
