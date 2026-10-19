package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.config.CosClientConfig;
import com.mofanyi.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 站点图片基础服务：Pexels 搜索 → 下载 → 上传 COS → 落库 app_image。
 * 供生成 agent 的 searchImage 工具、以及生成前的素材预取（{@link ImageMaterialPlanner}）共用。
 */
@Slf4j
@Service
public class SiteImageService {

    private static final int[] RESPONSIVE_MAX_EDGES = {640, 1280, 2400};
    private static final float[] RESPONSIVE_QUALITIES = {0.78f, 0.82f, 0.88f};

    private final PexelsClient pexelsClient;
    private final CosManager cosManager;
    private final CosClientConfig cosClientConfig;
    private final com.mofanyi.mapper.AppImageMapper appImageMapper;
    private final ToolRiskRegistry toolRiskRegistry;
    /** 图源列表（按 @Order：pexels → pixabay → …），编排只认这个接口，加源不用改本类 */
    private final List<ImageSource> imageSources;

    public SiteImageService(PexelsClient pexelsClient,
                            CosManager cosManager,
                            CosClientConfig cosClientConfig,
                            com.mofanyi.mapper.AppImageMapper appImageMapper,
                            ToolRiskRegistry toolRiskRegistry,
                            List<ImageSource> imageSources) {
        this.pexelsClient = pexelsClient;
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
        this.appImageMapper = appImageMapper;
        this.toolRiskRegistry = toolRiskRegistry;
        this.imageSources = imageSources;
    }

    /**
     * 搜索结果：候选图 + 命中的图源名（供文案标注来源）
     */
    private record SearchOutcome(List<ImageSource.ImageCandidate> images, String source) {
    }

    public record ImageVariant(int width, String url) {
    }

    /**
     * 正式上传结果：优先 URL 使用最高质量档；variants 用于 HTML srcset。
     */
    public record ResponsiveImage(String url, String originalUrl, int width, int height,
                                  List<ImageVariant> variants) {
    }

    private record DecodedSource(BufferedImage image, int originalWidth, int originalHeight) {
    }

    /**
     * 图源顺序回退：按 {@link ImageSource} 的 @Order 依次尝试，第一个有结果的源胜出。
     * 加新源只需新增一个实现类，本方法不动。
     */
    private SearchOutcome searchPhotos(String query, String orientation) {
        for (ImageSource source : imageSources) {
            List<ImageSource.ImageCandidate> images =
                    source.search(query, orientation, pexelsClient.getPageSize());
            if (!images.isEmpty()) {
                return new SearchOutcome(images, source.name());
            }
            log.info("[searchImage] 图源 {} 无结果，换下一个源，query={}", source.name(), query);
        }
        return new SearchOutcome(List.of(), "none");
    }

    /**
     * 搜索图片 → 逐张下载并上传 COS → 落库 app_image → 返回 COS URL 列表文本（含编号与尺寸，供模型挑选）
     */
    public String searchAndUploadImages(Long appId, String query, String orientation) {
        log.info("[searchImage] 被调用 query={} orientation={}", query, orientation);
        SearchOutcome outcome = searchPhotos(query, orientation);
        List<ImageSource.ImageCandidate> images = outcome.images();
        if (images.isEmpty()) {
            log.warn("[searchImage] 全部图源均无结果，query={}", query);
            return "未找到匹配的图片（所有图源均无结果），请换一个描述重新搜索，或不用图片直接用纯色/渐变背景";
        }
        boolean fromPixabay = "pixabay".equals(outcome.source());
        StringBuilder sb = new StringBuilder(fromPixabay
                ? "找到以下图片（来自 Pixabay 图库，可直接用 URL 嵌入代码）：\n"
                : "找到以下图片（可直接用 URL 嵌入代码，建议优先用列表中的 URL）：\n");
        int index = 1;
        for (ImageSource.ImageCandidate img : images) {
            try {
                ResponsiveImage uploaded = downloadAndUploadResponsive(appId, img);
                saveAppImage(appId, uploaded.url());
                sb.append(index).append(". URL=").append(uploaded.url())
                        .append(" 尺寸=").append(uploaded.width()).append("x").append(uploaded.height())
                        .append(" srcset=\"").append(toSrcset(uploaded.variants())).append('"')
                        .append("\n");
                index++;
            } catch (Exception e) {
                log.warn("图片上传 COS 失败，appId={}, url={}", appId, img.url(), e);
                sb.append(index).append(". 该图上传失败，请跳过\n");
                index++;
            }
        }
        return sb.toString();
    }

    /**
     * 将上传成功的图片 URL 落库到 app_image（记录该应用用到的嵌入图）。
     * sortOrder 基于该应用已有记录数累加，多次调用时编号不重复。
     */
    public void saveAppImage(Long appId, String cosUrl) {
        try {
            long existing = appImageMapper.selectCountByQuery(
                    com.mybatisflex.core.query.QueryWrapper.create().eq("appId", appId));
            com.mofanyi.entity.AppImage appImage = com.mofanyi.entity.AppImage.builder()
                    .appId(appId)
                    .imageUrl(cosUrl)
                    .imageType("embed")
                    .sortOrder((int) existing + 1)
                    .build();
            appImageMapper.insert(appImage);
        } catch (RuntimeException e) {
            log.warn("图片落库 app_image 失败，appId={}, url={}", appId, cosUrl, e);
        }
    }

    /**
     * 下载图片到本地临时文件，上传 COS，返回 COS URL；上传后删除本地临时文件
     */
    public String downloadAndUpload(Long appId, String imageUrl) throws Exception {
        // 工具风险分级：searchImage 是对外网络请求，出站地址必须在白名单内（防 SSRF）
        toolRiskRegistry.checkOutboundUrl(imageUrl);
        byte[] bytes = pexelsClient.downloadImage(imageUrl);
        return uploadBytes(appId, bytes, ".jpg");
    }

    /**
     * 从图库原始图生成响应式版本。筛选预览图只用于模型判断，不再作为正式网站资源。
     */
    public ResponsiveImage downloadAndUploadResponsive(Long appId,
                                                       ImageSource.ImageCandidate image) throws Exception {
        String originalUrl = StrUtil.blankToDefault(image.originalUrl(), image.url());
        byte[] originalBytes;
        try {
            originalBytes = downloadImageBytes(originalUrl);
        } catch (RuntimeException e) {
            if (originalUrl.equals(image.url())) {
                throw e;
            }
            log.warn("[素材] 原图下载失败，回退图库预览地址，url={}", originalUrl, e);
            originalBytes = downloadImageBytes(image.url());
        }
        DecodedSource decoded = decodeForResponsive(originalBytes);
        BufferedImage source = decoded.image();

        Map<Integer, ImageVariant> byWidth = new LinkedHashMap<>();
        for (int i = 0; i < RESPONSIVE_MAX_EDGES.length; i++) {
            int maxEdge = RESPONSIVE_MAX_EDGES[i];
            int width = scaledWidth(source, maxEdge);
            if (byWidth.containsKey(width)) {
                continue;
            }
            float quality = Math.max(source.getWidth(), source.getHeight()) <= maxEdge
                    ? RESPONSIVE_QUALITIES[RESPONSIVE_QUALITIES.length - 1]
                    : RESPONSIVE_QUALITIES[i];
            byte[] jpeg = resizeToJpeg(source, maxEdge, quality);
            String url = uploadBytes(appId, jpeg, ".jpg");
            byWidth.put(width, new ImageVariant(width, url));
            if (Math.max(source.getWidth(), source.getHeight()) <= maxEdge) {
                break;
            }
        }
        List<ImageVariant> variants = new ArrayList<>(byWidth.values());
        ImageVariant highest = variants.get(variants.size() - 1);
        log.info("[素材] 响应式图生成：source={}x{} variants={}",
                decoded.originalWidth(), decoded.originalHeight(), variants);
        return new ResponsiveImage(highest.url(), originalUrl,
                decoded.originalWidth(), decoded.originalHeight(), List.copyOf(variants));
    }

    public static String toSrcset(List<ImageVariant> variants) {
        if (variants == null || variants.isEmpty()) {
            return "";
        }
        return variants.stream()
                .map(variant -> variant.url() + " " + variant.width() + "w")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static byte[] resizeToJpeg(BufferedImage source, int maxEdge, float quality) throws Exception {
        int sourceMaxEdge = Math.max(source.getWidth(), source.getHeight());
        int targetWidth = source.getWidth();
        int targetHeight = source.getHeight();
        if (sourceMaxEdge > maxEdge) {
            double scale = (double) maxEdge / sourceMaxEdge;
            targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
            targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        }

        BufferedImage rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * 原图可能是数千万像素；先按头部尺寸做 source subsampling，避免完整解码导致堆内存峰值过高。
     */
    private static DecodedSource decodeForResponsive(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("无法创建图片输入流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("原图无法解码");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int originalWidth = reader.getWidth(0);
                int originalHeight = reader.getHeight(0);
                int maxOriginalEdge = Math.max(originalWidth, originalHeight);
                int highEdge = RESPONSIVE_MAX_EDGES[RESPONSIVE_MAX_EDGES.length - 1];
                int subsampling = Math.max(1, maxOriginalEdge / highEdge);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    throw new IOException("原图无法解码");
                }
                return new DecodedSource(image, originalWidth, originalHeight);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int scaledWidth(BufferedImage source, int maxEdge) {
        int sourceMaxEdge = Math.max(source.getWidth(), source.getHeight());
        if (sourceMaxEdge <= maxEdge) {
            return source.getWidth();
        }
        return Math.max(1, (int) Math.round(source.getWidth() * ((double) maxEdge / sourceMaxEdge)));
    }

    /**
     * 为视觉筛选下载原始字节，仍执行出站白名单与单图大小闸门。
     */
    public byte[] downloadImageBytes(String imageUrl) {
        toolRiskRegistry.checkOutboundUrl(imageUrl);
        byte[] bytes = pexelsClient.downloadImage(imageUrl);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("图片下载为空");
        }
        if (bytes.length > 32 * 1024 * 1024) {
            throw new IllegalStateException("图片超过 32MiB 上限：" + bytes.length);
        }
        return bytes;
    }

    /**
     * 把字节内容上传 COS（供 Mermaid 渲染图等本地生成图使用），返回 COS URL
     */
    public String uploadBytes(Long appId, byte[] bytes, String suffix) throws Exception {
        File tmpFile = File.createTempFile("appimg_", suffix);
        try {
            FileUtil.writeBytes(bytes, tmpFile);
            String key = "app-image/" + appId + "/" + UUID.randomUUID() + suffix;
            cosManager.putObject(key, tmpFile);
            return cosClientConfig.getHost() + "/" + key;
        } finally {
            FileUtil.del(tmpFile);
        }
    }
}
