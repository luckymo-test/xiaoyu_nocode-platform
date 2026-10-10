package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pexels 免费商用图库客户端：按语义描述搜索图片。
 * 只负责搜索，返回候选图列表；下载与上传 COS 由生成 Agent 的 searchImage 工具编排。
 *
 * @author 程序员luckymo
 */
@Slf4j
@Data
@Component
@Order(1)
@ConfigurationProperties(prefix = "pexels")
public class PexelsClient implements ImageSource {

    /**
     * Pexels API 地址
     */
    private static final String SEARCH_URL = "https://api.pexels.com/v1/search";

    /**
     * Pexels API key
     */
    private String apiKey;

    /**
     * 每次搜索返回的最大图片数
     */
    private int pageSize = 5;

    /**
     * 按语义描述搜索图片
     *
     * @param query       关键词描述
     * @param orientation 可选，landscape / portrait / square，可为空
     * @return 候选图片列表（含 CDN URL 与宽高信息）
     */
    public List<PexelsImage> searchImages(String query, String orientation) {
        return searchImages(query, orientation, pageSize);
    }

    /**
     * 按语义描述搜索图片（可指定本次候选数）。
     * 素材先行的预取需要"大候选池、少量入选"，与工具按需搜索的规模不同，故单列重载。
     *
     * @param perPage 本次返回的最大候选数
     */
    public List<PexelsImage> searchImages(String query, String orientation, int perPage) {
        List<PexelsImage> images = new ArrayList<>();
        if (StrUtil.isBlank(apiKey)) {
            log.warn("Pexels api-key 未配置，无法搜索图片");
            return images;
        }
        String body;
        int status;
        try {
            HttpResponse resp = HttpRequest.get(SEARCH_URL)
                    // Pexels 要求 Authorization: <api_key>，不带 "Bearer " 前缀（否则 401 Invalid API key）
                    .header("Authorization", apiKey)
                    .form("query", query)
                    .form("orientation", orientation == null || orientation.isBlank() ? null : orientation)
                    .form("per_page", perPage)
                    .timeout(10_000)
                    .execute();
            status = resp.getStatus();
            body = resp.body();
        } catch (Exception e) {
            log.warn("Pexels 搜索请求异常，query={}", query, e);
            return images;
        }
        if (status < 200 || status >= 300) {
            // 401/403/429/5xx 等：不静默，打日志便于排查（此前直接返回空且无日志）
            log.warn("Pexels 搜索失败 status={} query={} body={}",
                    status, query, StrUtil.maxLength(body == null ? "" : body, 200));
            return images;
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONArray photos = root.getJSONArray("photos");
            if (photos == null) {
                return images;
            }
            for (int i = 0; i < photos.size(); i++) {
                JSONObject photo = photos.getJSONObject(i);
                JSONObject src = photo.getJSONObject("src");
                PexelsImage img = new PexelsImage();
                img.setUrl(src.getStr("large"));
                img.setOriginalUrl(src.getStr("original"));
                img.setWidth(photo.getInt("width"));
                img.setHeight(photo.getInt("height"));
                img.setPhotographer(photo.getStr("photographer"));
                images.add(img);
            }
        } catch (Exception e) {
            log.warn("Pexels 响应解析失败，query={}", query, e);
        }
        return images;
    }

    // ---------- ImageSource 适配（图源 SPI） ----------

    @Override
    public String name() {
        return "pexels";
    }

    @Override
    public List<String> allowedHostSuffixes() {
        // 检索走 api.pexels.com，图片走 images.pexels.com，同一后缀即覆盖两者
        return List.of("pexels.com");
    }

    @Override
    public List<ImageCandidate> search(String query, String orientation, int perPage) {
        return searchImages(query, orientation, perPage).stream()
                .map(img -> new ImageCandidate(img.getUrl(), img.getOriginalUrl(),
                        img.getWidth(), img.getHeight(), name()))
                .toList();
    }

    /**
     * 下载图片字节
     *
     * @param url 图片 URL
     * @return 图片字节
     */
    public byte[] downloadImage(String url) {
        return HttpUtil.downloadBytes(url);
    }

    /**
     * Pexels 候选图片
     */
    @Data
    public static class PexelsImage {
        private String url;
        private String originalUrl;
        private Integer width;
        private Integer height;
        private String photographer;
    }
}
