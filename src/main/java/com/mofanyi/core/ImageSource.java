package com.mofanyi.core;

import java.util.List;

/**
 * 图源 SPI（plan-image-sources 的"图源统一收敛回 COS URL"落地）。
 *
 * <p><b>为什么要这层</b>：在此之前，"Pexels→Pixabay 顺序回退"这一套编排被写了两份
 * （{@link SiteImageService} 与 {@link ImageMaterialPlanner}），出站白名单又是第三处硬编码常量。
 * 加一家图源要改三个地方，漏一处就是"生成时能搜到、预取时搜不到"这种半截生效。
 * 收敛后：**新增图源 = 写一个实现类 + 声明允许的出站域名后缀**，编排与白名单自动生效。
 *
 * <p><b>实现约束</b>：实现类只做 HTTP 检索，<b>不要依赖 {@link SiteImageService}</b>
 * （后者依赖 {@link ToolRiskRegistry}，而后者要汇总本接口的所有实现，会构成循环依赖）。
 * "下载→COS→落库"这一步统一由 {@link SiteImageService} 负责，图源只提供 URL 与尺寸。
 */
public interface ImageSource {

    /**
     * 源标识（用于日志与排查）：pexels / pixabay / ...
     */
    String name();

    /**
     * 按语义检索候选图。**失败或无结果一律返回空列表，不抛异常**——上层靠"空"判断是否换下一个源。
     *
     * @param query       检索词（英文命中率更高）
     * @param orientation 可选：landscape / portrait / square（各实现按自身参数映射）
     * @param perPage     本次候选数上限
     */
    List<ImageCandidate> search(String query, String orientation, int perPage);

    /**
     * 允许出站的图片域名后缀（SSRF 白名单用）。
     * <b>检索 API 域名与图片 CDN 域名都要覆盖</b>——下载走的是图片 CDN（如 images.pexels.com）。
     */
    List<String> allowedHostSuffixes();

    /**
     * 统一候选图结构：字段对齐原 {@code PexelsClient.PexelsImage}，便于共用同一套"下载→COS→落库"。
     */
    record ImageCandidate(String url, String originalUrl, Integer width, Integer height, String source) {
    }
}
