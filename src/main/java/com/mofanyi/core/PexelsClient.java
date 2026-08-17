package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
@ConfigurationProperties(prefix = "pexels")
public class PexelsClient {

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
        List<PexelsImage> images = new ArrayList<>();
        if (StrUtil.isBlank(apiKey)) {
            log.warn("Pexels api-key 未配置，无法搜索图片");
            return images;
        }
        String body;
        try {
            body = HttpRequest.get(SEARCH_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .form("query", query)
                    .form("orientation", orientation == null || orientation.isBlank() ? null : orientation)
                    .form("per_page", pageSize)
                    .timeout(10_000)
                    .execute()
                    .body();
        } catch (Exception e) {
            log.warn("Pexels 搜索失败，query={}", query, e);
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
