package com.mofanyi.core;

import cn.hutool.http.HttpUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实原图与 COS 响应式上传探针，默认跳过：
 * {@code mvn -o test -Dtest=ResponsiveImageProbeTest -DcodegenProbe=true -DcodegenProbeCos=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
@EnabledIfSystemProperty(named = "codegenProbeCos", matches = "true")
class ResponsiveImageProbeTest {

    @Autowired
    private List<ImageSource> imageSources;

    @Autowired
    private SiteImageService siteImageService;

    @Test
    @DisplayName("探针：图库原图生成可访问的多档响应式图片")
    void uploadsRealResponsiveVariants() throws Exception {
        ImageSource.ImageCandidate candidate = imageSources.stream()
                .flatMap(source -> source.search("nature landscape photography", null, 3).stream())
                .filter(image -> image.width() != null && image.height() != null)
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(candidate != null, "图库密钥未配置或无候选图");
        assertFalse(candidate.originalUrl() == null || candidate.originalUrl().isBlank(),
                "图库候选必须提供原图地址");
        assertNotEquals(candidate.url(), candidate.originalUrl(),
                "筛选预览 URL 与正式原图 URL 应分离");

        SiteImageService.ResponsiveImage uploaded =
                siteImageService.downloadAndUploadResponsive(990001000000000004L, candidate);
        System.out.println("[responsive-image-probe] source=" + uploaded.originalUrl()
                + " original=" + uploaded.width() + "x" + uploaded.height()
                + " variants=" + uploaded.variants());

        assertTrue(uploaded.width() >= 800 && uploaded.height() >= 500);
        assertFalse(uploaded.variants().isEmpty());
        assertTrue(uploaded.variants().size() <= 3);
        for (SiteImageService.ImageVariant variant : uploaded.variants()) {
            byte[] bytes = HttpUtil.downloadBytes(variant.url());
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertTrue(image != null && image.getWidth() > 0);
            assertEquals(variant.width(), image.getWidth(),
                    "srcset 宽度描述必须与 COS 实际图片宽度一致");
            assertTrue(Math.max(image.getWidth(), image.getHeight()) <= 2400);
        }
    }
}
