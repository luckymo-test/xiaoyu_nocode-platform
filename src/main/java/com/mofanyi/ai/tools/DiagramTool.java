package com.mofanyi.ai.tools;

import com.mofanyi.core.MermaidRenderer;
import com.mofanyi.core.SiteImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

/**
 * diagram 工具：把模型给出的 Mermaid 文本渲染成 PNG，上传 COS 后返回可直接嵌入的图片 URL。
 * 适用于架构图 / 流程图 / 时序图 / 状态图 / ER / 思维导图等示意图。
 */
@Slf4j
@Component
public class DiagramTool {

    public record DiagramRequest(String mermaid) {
    }

    private static final int MAX_MERMAID_LEN = 3000;

    private final MermaidRenderer mermaidRenderer;
    private final SiteImageService siteImageService;

    public DiagramTool(MermaidRenderer mermaidRenderer, SiteImageService siteImageService) {
        this.mermaidRenderer = mermaidRenderer;
        this.siteImageService = siteImageService;
    }

    public ToolCallback build(Long appId) {
        return FunctionToolCallback.builder("diagram", (DiagramRequest req) -> {
            String text = (req.mermaid() == null ? "" : req.mermaid()).trim();
            if (text.isEmpty()) {
                return "mermaid 内容不能为空";
            }
            if (text.length() > MAX_MERMAID_LEN) {
                return "mermaid 内容过长，请精简（当前超过 " + MAX_MERMAID_LEN + " 字符）";
            }
            try {
                byte[] png = mermaidRenderer.render(text);
                String cosUrl = siteImageService.uploadBytes(appId, png, ".png");
                siteImageService.saveAppImage(appId, cosUrl);
                log.info("[diagram] 渲染成功 appId={} url={}", appId, cosUrl);
                return "渲染成功，图片地址（直接用于 <img src> 或 background-image）：" + cosUrl;
            } catch (Exception e) {
                log.warn("[diagram] 渲染失败 appId={}", appId, e);
                return "渲染失败：" + e.getMessage()
                        + "。请检查 Mermaid 语法（可用 flowchart/sequence/stateDiagram/erDiagram/timeline/classDiagram 等），或改用手绘图形/纯文字段落代替。";
            }
        })
                .description("需要架构图/流程图/时序图/ER/状态图等示意图时调用本工具。mermaid 为合法的 Mermaid 语法文本（如 flowchart TD; A-->B）。渲染后会返回图片 URL，直接在页面 <img src> 或 CSS background-image 引用即可。")
                .inputType(DiagramRequest.class)
                .build();
    }
}
