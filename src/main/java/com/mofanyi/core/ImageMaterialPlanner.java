package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 素材先行（plan-refactor 6.6 / plan-agent-pipeline 6.3）：页面构建<b>之前</b>把图片素材准备好，
 * 产出一份"素材清单"作为事实注入 prompt，让页面围绕真实素材（URL + 尺寸 + 宽高比）来设计。
 *
 * <p><b>为什么要有它</b>：原先是"页面先生成、事后用正则补图"（{@code ImageAutoFiller}，已删除）——
 * 页面在不知道有哪些素材的情况下写出来，必然留空占位，再由正则去猜，优化的是"不为空"而不是"好"。
 *
 * <p><b>本步（10）的范围</b>：多 query 扩大候选池 + 去重 + 最小分辨率闸门 + 宽高比分桶 + top-K 上传。
 * 更深的闸门（感知去重/水印启发式/视觉打分）与素材加工（裁切/压缩/命名）是后续 11~13 步的事。
 */
@Slf4j
@Component
public class ImageMaterialPlanner {

    /**
     * 单次预取的素材上限（top-K）
     */
    private static final int MAX_MATERIALS = 8;

    /**
     * 每个 query 拉取的候选数（候选池；工具按需搜索仍走配置里的 pageSize）
     */
    private static final int CANDIDATES_PER_QUERY = 12;

    /**
     * 分辨率闸门：低于此尺寸的图不用（避免放大后糊）
     */
    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 500;

    /**
     * 单个宽高比分桶最多入选几张——避免清单里清一色横向图
     */
    private static final int MAX_PER_RATIO_BUCKET = 4;

    private final SiteImageService siteImageService;
    private final ChatModel chatModel;
    /** 图源列表（按 @Order），与 searchImage 工具共用同一套编排 */
    private final List<ImageSource> imageSources;

    public ImageMaterialPlanner(SiteImageService siteImageService,
                                @Qualifier("openAiChatModel") ChatModel chatModel,
                                List<ImageSource> imageSources) {
        this.siteImageService = siteImageService;
        this.chatModel = chatModel;
        this.imageSources = imageSources;
    }

    /**
     * 一份可用素材：COS URL + 尺寸 + 宽高比分桶 + 来源 query
     */
    public record Material(String url, int width, int height, String ratio, String query) {

        /**
         * 供 prompt 使用的一行描述
         */
        public String describe() {
            return url + "（" + width + "x" + height + "，" + ratio + "）";
        }
    }

    /**
     * 预取素材清单。任何一步失败都退化为"清单为空"（调用方据此回退到按需 searchImage），不抛异常。
     *
     * @param appId       应用 id（COS key 与落库用）
     * @param userMessage 用户需求（用于提取搜索关键词）
     * @return 已上传 COS 的素材清单，可能为空
     */
    public List<Material> plan(Long appId, String userMessage) {
        try {
            List<String> queries = buildQueries(userMessage);
            if (queries.isEmpty()) {
                return List.of();
            }
            // ① 候选池：中文 query + 模型扩写的英文 query，分别搜索（Pexels 优先、Pixabay 兜底）
            List<ImageSource.ImageCandidate> candidates = new ArrayList<>();
            for (String query : queries) {
                candidates.addAll(searchCandidates(query));
            }
            int chineseOnly = countUsable(candidates);
            // ①' 追加英文 query。实测（2026-10-10）三点推动了这个改法：
            //   1) Pexels/Pixabay 是英文索引，中文 query 能出结果但**多 query 的第二条恒为空**（3 次运行候选都恰好 12 = 单 query 上限）；
            //   2) 中文冷门主题（汉服）虽也够 8 张，但池子只有 12 张，挑选余地小；
            //   3) 先做的是"命中不足才扩写"，实测中文已够数 → 从不触发，等于白写。
            //   代价是每次生成多一次小模型调用（原本的"xx background"变体是零成本但零收益）。
            List<String> englishQueries = expandQueriesWithModel(userMessage);
            if (!englishQueries.isEmpty()) {
                for (String query : englishQueries) {
                    candidates.addAll(searchCandidates(query));
                }
                log.info("[素材] query：中文可用 {} 张；英文扩写 {}（候选池增至 {} 张）",
                        chineseOnly, englishQueries, candidates.size());
            }
            // ② 质量闸门：去重 + 最小分辨率（更深的闸门见 11~13 步）
            Map<String, ImageSource.ImageCandidate> deduped = new LinkedHashMap<>();
            int rejectedBySize = 0;
            for (ImageSource.ImageCandidate image : candidates) {
                if (StrUtil.isBlank(image.url()) || deduped.containsKey(image.url())) {
                    continue;
                }
                if (image.width() == null || image.height() == null
                        || image.width() < MIN_WIDTH || image.height() < MIN_HEIGHT) {
                    rejectedBySize++;
                    continue;
                }
                deduped.put(image.url(), image);
            }
            // ③ 挑选：按宽高比分桶限量，避免清单清一色
            List<ImageSource.ImageCandidate> selected = selectBalanced(deduped.values());
            // ④ 上传 COS（下载失败/上传失败的单张跳过）
            List<Material> materials = new ArrayList<>();
            for (ImageSource.ImageCandidate image : selected) {
                try {
                    String cosUrl = siteImageService.downloadAndUpload(appId, image.url());
                    siteImageService.saveAppImage(appId, cosUrl);
                    materials.add(new Material(cosUrl, image.width(), image.height(),
                            ratioBucket(image.width(), image.height()), ""));
                } catch (Exception e) {
                    log.warn("[素材] 上传失败，跳过，url={}", image.url(), e);
                }
            }
            log.info("[素材] 预取完成：候选 {} 张（分辨率淘汰 {}），入选 {} 张，appId={}",
                    candidates.size(), rejectedBySize, materials.size(), appId);
            if (!materials.isEmpty()) {
                // 逐张列出清单：验收"页面图片 URL 全部来自预选清单"时要拿它比对
                materials.forEach(m -> log.info("[素材] 清单 {}x{} {} {}", m.width(), m.height(), m.ratio(), m.url()));
            }
            return materials;
        } catch (RuntimeException e) {
            log.warn("[素材] 预取失败，退化为按需搜图，appId={}", appId, e);
            return List.of();
        }
    }

    /**
     * 把清单渲染成注入 prompt 的"事实"段落；清单为空返回空串（调用方不要注入）。
     */
    public String toPromptSection(List<Material> materials) {
        if (materials == null || materials.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【素材清单（已在生成前备好，可直接引用）】\n")
                .append("下列图片已下载并上传到 CDN，尺寸可靠。页面只要涉及图片，**必须直接使用清单中的 URL**，")
                .append("不要改写 URL、不要自己编造或使用清单以外的图片地址（含占位图站）。\n");
        int index = 1;
        for (Material material : materials) {
            sb.append(index++).append(". ").append(material.describe()).append('\n');
        }
        sb.append("""
                选用建议：横幅/背景/卡片大图用 landscape；人物/海报/竖向卡片用 portrait；头像/图标位用 square。
                清单不够用时再调用 searchImage（它会返回同批素材，同样可直接引用）。
                图注请写有意义的 alt 文案。""");
        return sb.toString();
    }

    /**
     * 从用户需求里提取搜索关键词：去掉"生成/一个/页面"这类无意义词，保留内容词。
     * （原 {@code ImageAutoFiller.extractImageQuery} 的等价能力，随其删除迁到这里。）
     */
    String extractKeywords(String userMessage) {
        if (StrUtil.isBlank(userMessage)) {
            return "";
        }
        String cleaned = userMessage
                .replaceAll("[，。！？,.!?、\\s]+", " ")
                .replaceAll("(生成|制作|创建|设计|一个|一款|页面|网页|网站|首页|需要|希望|要求|展示|提供|带|用|的|风格|简洁|美观|大气)", " ")
                .trim();
        if (cleaned.length() < 2) {
            return "";
        }
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, 6); i++) {
            if (words[i].isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * 多 query：关键词本身 + 具象一点的"背景"变体，扩大候选池（候选池扩容的深度做法见 11 步）
     */
    private List<String> buildQueries(String userMessage) {
        String keywords = extractKeywords(userMessage);
        if (keywords.isBlank()) {
            return List.of();
        }
        return List.of(keywords);
    }

    /**
     * 单个 query 的候选：按 @Order 依次问各图源，第一个有结果的源胜出（与 searchImage 工具同源同序）。
     */
    private List<ImageSource.ImageCandidate> searchCandidates(String query) {
        for (ImageSource source : imageSources) {
            List<ImageSource.ImageCandidate> images = source.search(query, null, CANDIDATES_PER_QUERY);
            if (!images.isEmpty()) {
                log.info("[素材] query={} 命中源={} 候选={} 张", query, source.name(), images.size());
                return images;
            }
        }
        log.info("[素材] query={} 全部图源无结果", query);
        return List.of();
    }

    private List<ImageSource.ImageCandidate> selectBalanced(
            Iterable<ImageSource.ImageCandidate> candidates) {
        Map<String, Integer> perBucket = new LinkedHashMap<>();
        List<ImageSource.ImageCandidate> selected = new ArrayList<>();
        for (ImageSource.ImageCandidate image : candidates) {
            if (selected.size() >= MAX_MATERIALS) {
                break;
            }
            String bucket = ratioBucket(image.width(), image.height());
            int used = perBucket.getOrDefault(bucket, 0);
            if (used >= MAX_PER_RATIO_BUCKET) {
                continue;
            }
            perBucket.put(bucket, used + 1);
            selected.add(image);
        }
        return selected;
    }

    /**
     * 宽高比分桶：landscape / square / portrait
     */
    private static String ratioBucket(int width, int height) {
        double ratio = (double) width / Math.max(1, height);
        if (ratio >= 1.25) {
            return "landscape";
        }
        return ratio <= 0.8 ? "portrait" : "square";
    }

    /**
     * 先按闸门粗筛一遍数量，用于判断"中文 query 够不够用"（不去重、不上传）
     */
    private static int countUsable(List<ImageSource.ImageCandidate> candidates) {
        return (int) candidates.stream()
                .filter(image -> StrUtil.isNotBlank(image.url()))
                .filter(image -> image.width() != null && image.height() != null
                        && image.width() >= MIN_WIDTH && image.height() >= MIN_HEIGHT)
                .count();
    }

    /**
     * 用模型把中文需求转成 2~3 个英文图库检索词。
     *
     * <p>为什么要它：Pexels/Pixabay 的索引以英文为主（Pixabay 官方也建议英文关键词），而用户需求基本都是中文——
     * 直接把中文丢过去，冷门主题常常命中个位数。改造只在"中文命中不足"时触发，故不给常态生成加时延。
     * 失败/解析不出就返回空列表，调用方按"没有扩写"继续（绝不因为扩写失败而让预取失败）。
     */
    private List<String> expandQueriesWithModel(String userMessage) {
        try {
            String answer = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你是图库检索助手。把用户的中文需求转成 2~3 个英文图片检索词，"
                            + "面向 Pexels/Pixabay 这类摄影图库，尽量具体（主体 + 场景 + 风格）。"
                            + "只输出检索词，用英文逗号分隔，不要编号、不要解释、不要引号。"),
                    new UserMessage(userMessage))))
                    .getResult().getOutput().getText();
            if (StrUtil.isBlank(answer)) {
                return List.of();
            }
            Set<String> queries = new LinkedHashSet<>();
            for (String part : answer.split("[,，\\n]")) {
                String query = part.trim().replaceAll("^[\\d.、)\\s]+", "").replace("\"", "");
                if (!query.isBlank() && query.length() <= 60) {
                    queries.add(query);
                }
                if (queries.size() >= 3) {
                    break;
                }
            }
            return new ArrayList<>(queries);
        } catch (RuntimeException e) {
            log.warn("[素材] 英文 query 扩写失败，沿用中文 query", e);
            return List.of();
        }
    }
}
