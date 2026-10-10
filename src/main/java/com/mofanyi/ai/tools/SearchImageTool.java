package com.mofanyi.ai.tools;

import com.mofanyi.core.ImageMaterialPlanner;
import com.mofanyi.core.SiteImageService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * searchImage 工具：按语义从 Pexels 搜图 → 上传 COS → 返回可直接嵌入的 URL 列表。
 * 具体能力委托 {@link SiteImageService}（生成后补图也复用它）。
 *
 * <p><b>素材先行时（本次生成已预取到清单）</b>：本工具改为返回<b>预取清单</b>而不去实时搜图，
 * 这样页面里的图片 URL 一定落在清单内（验收："图片主导页面的图片 URL 全部来自预选清单"）。
 * 预取清单为空（图源不可用等）时才回退成实时搜索。
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
        return build(appId, List.of());
    }

    /**
     * 带预取素材的构建：清单非空时，工具只从清单取材
     */
    public ToolCallback build(Long appId, List<ImageMaterialPlanner.Material> materials) {
        boolean hasMaterials = materials != null && !materials.isEmpty();
        return FunctionToolCallback.builder("searchImage", (SearchImageRequest req) -> {
            if (req.query() == null || req.query().isBlank()) {
                return "查询描述不能为空";
            }
            if (hasMaterials) {
                return listMaterials(materials);
            }
            return siteImageService.searchAndUploadImages(appId, req.query(), req.orientation());
        })
                .description(hasMaterials
                        ? "返回本次生成已预取的素材清单（图片均已上传 CDN）。直接用清单里的 URL，不要改用其他图片地址。query/orientation 仅作占位。"
                        : "需要为页面配图时调用本工具。query 为图片的语义描述（如\"科技感蓝紫色渐变背景\"），orientation 可选（landscape 横向 / portrait 纵向 / square 方形）。调用后返回若干张已上传 COS 的图片 URL，直接在代码的 <img src> 或 CSS background-image 中引用即可。")
                .inputType(SearchImageRequest.class)
                .build();
    }

    /**
     * 把预取清单渲染成工具返回值
     */
    private String listMaterials(List<ImageMaterialPlanner.Material> materials) {
        StringBuilder sb = new StringBuilder("以下是本次生成已预取的素材（已上传 CDN，可直接引用）：\n");
        int index = 1;
        for (ImageMaterialPlanner.Material material : materials) {
            sb.append(index++).append(". ").append(material.describe()).append('\n');
        }
        sb.append("请从清单中挑选，不要重复使用同一张图，也不要使用清单以外的图片地址。");
        return sb.toString();
    }
}
