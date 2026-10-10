package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.config.CosClientConfig;
import com.mofanyi.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * 站点图片基础服务：Pexels 搜索 → 下载 → 上传 COS → 落库 app_image。
 * 供生成 agent 的 searchImage 工具、以及生成前的素材预取（{@link ImageMaterialPlanner}）共用。
 */
@Slf4j
@Service
public class SiteImageService {

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
                String cosUrl = downloadAndUpload(appId, img.url());
                saveAppImage(appId, cosUrl);
                sb.append(index).append(". URL=").append(cosUrl)
                        .append(" 尺寸=").append(img.width()).append("x").append(img.height())
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
