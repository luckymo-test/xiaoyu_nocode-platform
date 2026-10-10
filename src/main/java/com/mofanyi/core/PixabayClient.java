package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
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
 * Pixabay 免费图库客户端：按语义搜索图片，作为 Pexels 失效/无结果时的备选图源。
 * 字段尽量对齐 {@link PexelsClient.PexelsImage}，方便上层共用同一套"下载→COS→落库"。
 * 注意（官方约束）：
 * - 限流：每 60s 100 次（按 API key 计），非 2xx 时错误体是纯文本而非 JSON，只打日志按"无结果"回退；
 * - 禁止永久 hotlink：图片 URL 只能临时展示/立即下载，正式用必须先下载到服务器（本工程即下载后传 COS）。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "pixabay")
public class PixabayClient {

    private static final String SEARCH_URL = "https://pixabay.com/api/";

    private String apiKey;

    /**
     * 每次搜索返回的最大图片数
     */
    private int pageSize = 5;

    /**
     * 按语义描述搜索图片（摄影/实拍），字段收敛为 PexelsImage 方便上层复用
     *
     * @param query       关键词（建议英文）
     * @param orientation 可选 horizontal / vertical / all，可为空
     * @return 候选图片列表；失败/无结果返回空列表（不抛异常）
     */
    public List<PexelsClient.PexelsImage> searchImages(String query, String orientation) {
        return searchImages(query, orientation, pageSize);
    }

    /**
     * 按语义描述搜索图片（可指定本次候选数）——素材先行预取用
     */
    public List<PexelsClient.PexelsImage> searchImages(String query, String orientation, int perPage) {
        List<PexelsClient.PexelsImage> images = new ArrayList<>();
        if (StrUtil.isBlank(apiKey)) {
            log.warn("Pixabay api-key 未配置，无法搜索图片");
            return images;
        }
        HttpResponse resp;
        try {
            resp = HttpRequest.get(SEARCH_URL)
                    .form("key", apiKey)
                    .form("q", query)
                    .form("image_type", "photo")
                    .form("safesearch", "true")
                    .form("orientation", orientation == null || orientation.isBlank() ? "all" : orientation)
                    .form("per_page", perPage)
                    .timeout(10_000)
                    .execute();
        } catch (Exception e) {
            log.warn("Pixabay 搜索请求异常，query={}", query, e);
            return images;
        }
        int status = resp.getStatus();
        String body = resp.body();
        if (status < 200 || status >= 300) {
            // 错误体为纯文本（如 429 API rate limit exceeded），非 JSON，只打日志
            log.warn("Pixabay 搜索失败 status={} query={} body={}",
                    status, query, StrUtil.maxLength(body == null ? "" : body, 200));
            return images;
        }
        try {
            JSONObject root = JSONUtil.parseObj(body);
            JSONArray hits = root.getJSONArray("hits");
            if (hits == null) {
                return images;
            }
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                PexelsClient.PexelsImage img = new PexelsClient.PexelsImage();
                // 大图走 largeImageURL(1280)；webformatURL 标注 24h 有效，不用于持久下载
                img.setUrl(StrUtil.blankToDefault(hit.getStr("largeImageURL"),
                        hit.getStr("webformatURL")));
                img.setOriginalUrl(hit.getStr("imageURL"));
                img.setWidth(hit.getInt("imageWidth"));
                img.setHeight(hit.getInt("imageHeight"));
                img.setPhotographer(hit.getStr("user"));
                images.add(img);
            }
        } catch (Exception e) {
            log.warn("Pixabay 响应解析失败，query={}", query, e);
            return new ArrayList<>();
        }
        return images;
    }
}
