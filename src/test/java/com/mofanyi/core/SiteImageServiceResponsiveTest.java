package com.mofanyi.core;

import com.mofanyi.config.CosClientConfig;
import com.mofanyi.manager.CosManager;
import com.mofanyi.mapper.AppImageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteImageServiceResponsiveTest {

    @Test
    @DisplayName("正式上传从 originalUrl 生成三档响应式 JPEG")
    void generatesResponsiveVariantsFromOriginal() throws Exception {
        PexelsClient pexels = mock(PexelsClient.class);
        CosManager cosManager = mock(CosManager.class);
        CosClientConfig cosConfig = mock(CosClientConfig.class);
        ToolRiskRegistry riskRegistry = mock(ToolRiskRegistry.class);
        when(cosConfig.getHost()).thenReturn("https://cdn.example");
        when(pexels.downloadImage("https://source.example/original.jpg"))
                .thenReturn(jpeg(3200, 2000));
        SiteImageService service = new SiteImageService(
                pexels, cosManager, cosConfig, mock(AppImageMapper.class),
                riskRegistry, List.of());

        SiteImageService.ResponsiveImage uploaded = service.downloadAndUploadResponsive(
                9L, new ImageSource.ImageCandidate(
                        "https://source.example/preview.jpg",
                        "https://source.example/original.jpg",
                        3200, 2000, "pexels"));

        assertEquals(3200, uploaded.width());
        assertEquals(2000, uploaded.height());
        assertEquals("https://source.example/original.jpg", uploaded.originalUrl());
        assertEquals(3, uploaded.variants().size());
        assertEquals(List.of(640, 1280, 2400),
                uploaded.variants().stream().map(SiteImageService.ImageVariant::width).toList());
        assertEquals(uploaded.variants().get(2).url(), uploaded.url());
        assertTrue(SiteImageService.toSrcset(uploaded.variants()).contains("2400w"));
        verify(pexels).downloadImage("https://source.example/original.jpg");
        verify(pexels, never()).downloadImage("https://source.example/preview.jpg");
        verify(cosManager, org.mockito.Mockito.times(3))
                .putObject(anyString(), any(File.class));
    }

    @Test
    @DisplayName("小图不放大、不生成重复宽度档位")
    void doesNotUpscaleSmallImages() throws Exception {
        PexelsClient pexels = mock(PexelsClient.class);
        CosManager cosManager = mock(CosManager.class);
        CosClientConfig cosConfig = mock(CosClientConfig.class);
        when(cosConfig.getHost()).thenReturn("https://cdn.example");
        when(pexels.downloadImage("https://source.example/small.jpg"))
                .thenReturn(jpeg(500, 300));
        SiteImageService service = new SiteImageService(
                pexels, cosManager, cosConfig, mock(AppImageMapper.class),
                mock(ToolRiskRegistry.class), List.of());

        SiteImageService.ResponsiveImage uploaded = service.downloadAndUploadResponsive(
                9L, new ImageSource.ImageCandidate(
                        "https://source.example/small.jpg",
                        "https://source.example/small.jpg",
                        500, 300, "pexels"));

        assertEquals(1, uploaded.variants().size());
        assertEquals(500, uploaded.variants().get(0).width());
        verify(cosManager).putObject(anyString(), any(File.class));
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
