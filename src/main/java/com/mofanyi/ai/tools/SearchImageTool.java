package com.mofanyi.ai.tools;

import com.mofanyi.core.SiteImageService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

/**
 * searchImage 工具：按语义从 Pexels 搜图 → 上传 COS → 返回可直接嵌入的 URL 列表。
 * 具体能力委托 {@link SiteImageService}（生成后补图也复用它）。
 */
@Component
public class SearchImageTool {

    public record SearchImageRequest(String query, String orientation) {
    }

    private final SiteImageService siteImageService;

    public SearchImageTool(SiteImageService siteImageService) {
        this.siteImageService = siteImageService;
    }

    public ToolCallback build(Long appId) {
        return FunctionToolCallback.builder("searchImage", (SearchImageRequest req) -> {
            if (req.query() == null || req.query().isBlank()) {
                return "查询描述不能为空";
            }
            return siteImageService.searchAndUploadImages(appId, req.query(), req.orientation());
        })
                .description("需要为页面配图时调用本工具。query 为图片的语义描述（如\"科技感蓝紫色渐变背景\"），orientation 可选（landscape 横向 / portrait 纵向 / square 方形）。调用后返回若干张已上传 COS 的图片 URL，直接在代码的 <img src> 或 CSS background-image 中引用即可。")
                .inputType(SearchImageRequest.class)
                .build();
    }
}
