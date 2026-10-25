package com.mofanyi.template;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class TemplateRegistry {

    private final Map<String, TemplateBundle> templates = new LinkedHashMap<>();

    public TemplateRegistry() {
        Set<String> allProfiles = Set.of(
                "html-static", "multi-file-static", "vue-vite",
                "react-vite-ts", "next-static-export");
        register(new TemplateBundle(
                "default-neutral", "3", "克制信息型",
                allProfiles,
                List.of("hero", "content", "cta"),
                visualTokens("#18212b", "#d97706", "#f5f1e8", "4/8/16/24/40/64/96",
                        "最大 1200px / 12 栏 / 24px gutter", "0～8px；只用于输入和媒体",
                        "字号 14/16/20/32/56；正文行高 1.7", "留白优先，线框和轻阴影辅助",
                        "160～240ms；只做进入和状态反馈"),
                "建立内容优先的编辑式骨架：大标题左对齐，非必要不使用卡片，区块通过留白和细线分隔。"
                        + "视觉记忆点是跨栏大标题与低饱和背景中的单一强调色。"
                        + "禁止紫蓝渐变、满屏圆角卡片和无数据支撑的统计模块。",
                null));
        register(new TemplateBundle(
                "saas-product", "1", "SaaS 产品叙事",
                allProfiles,
                List.of("problem", "product-proof", "feature-story", "workflow", "objection", "cta"),
                visualTokens("#101613", "#c6ff4a", "#f2f0e8", "4/8/16/28/44/72/112",
                        "最大 1280px / 非对称 7:5 双栏", "10px；按钮和截图容器",
                        "字号 13/16/22/40/72；标题紧凑、正文舒展", "产品截图与大面积留白交替",
                        "滚动进入 240～360ms；支持 reduced-motion"),
                "以真实产品证据组织叙事，先讲问题和结果，再展示界面与工作流；不得虚构客户 Logo 和统计。"
                        + "视觉记忆点是超大结果陈述与产品截图的非对称叠放。"
                        + "避免功能卡三连、空泛口号和紫色渐变 SaaS 模板。",
                null));
        register(new TemplateBundle(
                "data-dashboard", "1", "数据密集控制台",
                allProfiles,
                List.of("status-summary", "trend", "breakdown", "table", "activity", "actions"),
                visualTokens("#0e141b", "#38bdf8", "#e8eef5", "4/8/12/16/24/32/48",
                        "最大 1600px / 12 栏紧凑网格", "4～8px；信息容器",
                        "字号 12/14/18/28/44；数字使用等宽或表格数字", "图表、表格和状态色控制密度",
                        "120～200ms；避免图表和表格持续动画"),
                "按“总览 → 趋势 → 异常 → 明细 → 操作”组织信息，状态色必须同时配合文字或图标。"
                        + "视觉记忆点是清晰的数据节奏和统一状态语义。"
                        + "禁止所有指标同样抢眼、无标签图表和装饰性仪表盘光效。",
                null));
        register(new TemplateBundle(
                "portfolio-studio", "1", "个人作品集",
                allProfiles,
                List.of("identity", "selected-work", "project-story", "process", "about", "contact"),
                visualTokens("#f1ece2", "#1d4ed8", "#161616", "4/8/20/36/64/96/144",
                        "最大 1440px / 非均匀作品网格", "0px；图片和分区直角",
                        "字号 13/16/22/42/88；标题可有编辑式比例", "作品图片主导，文字克制",
                        "图片进入 300～420ms；只做一次"),
                "先建立个人身份和工作方法，再用 2～4 个完整项目故事证明能力；少而深，避免同尺寸卡片瀑布。"
                        + "视觉记忆点是跨尺度作品图和编辑式大标题。"
                        + "禁止无意义英文口号、通用头像和把作品塞进模板卡片。",
                null));
        register(new TemplateBundle(
                "commerce-catalog", "1", "商品目录与品牌电商",
                allProfiles,
                List.of("campaign", "categories", "featured-products", "materials", "trust", "purchase"),
                visualTokens("#f5f1e8", "#d14b2f", "#1c1917", "4/8/16/24/40/64/96",
                        "最大 1320px / 商品 2～4 列响应式", "2～6px；商品图和购买按钮",
                        "字号 12/14/18/32/60；价格使用表格数字", "商品图统一光感但构图有变化",
                        "200ms hover；加入购物车使用明确状态变化"),
                "以商品、材质、使用场景和购买决策组织内容；没有真实评价和库存时不得伪造。"
                        + "视觉记忆点是品牌色与高质量商品图的强对比。"
                        + "禁止廉价倒计时、假折扣、满屏促销贴纸和同尺寸商品卡堆砌。",
                null));
        register(new TemplateBundle(
                "event-landing", "1", "活动与会议落地页",
                allProfiles,
                List.of("event-hero", "speakers", "agenda", "venue", "registration", "faq"),
                visualTokens("#14110f", "#ff6b35", "#faf4e8", "4/8/16/24/40/64/96",
                        "最大 1280px / 12 栏时间轴", "6～12px；日程和报名组件",
                        "字号 13/16/20/36/76；日期和地点需要高辨识度", "人物、场地和议程节奏明确",
                        "动效服务于议程展开和报名反馈"),
                "首屏优先说明活动主题、时间、地点和报名动作；议程与讲者必须有真实结构。"
                        + "视觉记忆点是醒目的日期排版和跨栏议程。"
                        + "禁止虚构讲者、无意义倒计时和重复报名按钮。",
                null));
        register(new TemplateBundle(
                "photography-editorial", "3", "摄影杂志画廊",
                allProfiles,
                List.of("cinematic-hero", "masonry-gallery", "story-strip", "photographer-bio", "contact-cta"),
                visualTokens("#0d0d0f", "#c6a869", "#f5f2ea", "4/8/16/28/48/80/120",
                        "最大 1500px / 12 栏杂志网格", "0～4px；图片和标签",
                        "字号 13/16/21/38/78；展示字体可用衬线", "全景、中景、细节和非均匀图片节奏",
                        "320～480ms；图片淡入或轻微位移"),
                "使用杂志编辑式构图：强标题、留白、非均匀图片节奏和明确跨栏关系。"
                        + "画廊必须有全景、中景和细节节奏，纵向照片尊重肖像比例，禁止裁成不可读细长条。"
                        + "视觉记忆点是跨尺度照片与克制的金色强调。",
                null));
        register(new TemplateBundle(
                "editorial-story", "1", "品牌编辑故事",
                allProfiles,
                List.of("editorial-hero", "manifesto", "story-chapters", "evidence", "footer"),
                visualTokens("#f7f2e8", "#9f2d20", "#171512", "4/8/20/36/60/92/136",
                        "最大 1360px / 非对称 8:4", "0px；正文和图片不使用圆角",
                        "字号 13/16/22/44/92；标题采用强对比编辑比例", "图片作为章节证据，混合远景与细节",
                        "章节进入 320～460ms；尊重 reduced-motion"),
                "用章节化叙事建立品牌观点、事实和证据链；标题与正文形成强对比。"
                        + "视觉记忆点是大面积正文留白与跨栏图片。"
                        + "禁止把品牌故事拆成同尺寸功能卡或虚构荣誉。",
                null));
    }

    public TemplateBundle resolve(String hint, String userMessage, String profileId) {
        ThrowUtils.throwIf(profileId == null || profileId.isBlank(), ErrorCode.PARAMS_ERROR);
        String requested = hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
        if (requested.isBlank() || "auto".equals(requested)) {
            String text = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
            if (containsAny(text, "摄影", "照片墙", "photography", "gallery")) {
                requested = "photography-editorial";
            } else if (containsAny(text, "仪表盘", "数据看板", "后台", "dashboard", "analytics")) {
                requested = "data-dashboard";
            } else if (containsAny(text, "saas", "产品落地页", "工作流", "workflow", "product")) {
                requested = "saas-product";
            } else if (containsAny(text, "作品集", "个人主页", "设计师", "portfolio")) {
                requested = "portfolio-studio";
            } else if (containsAny(text, "电商", "商品", "购物", "shop", "commerce")) {
                requested = "commerce-catalog";
            } else if (containsAny(text, "活动", "会议", "发布会", "conference", "event")) {
                requested = "event-landing";
            } else if (containsAny(text, "品牌故事", "杂志", "editorial", "品牌")) {
                requested = "editorial-story";
            } else {
                requested = "default-neutral";
            }
        }
        TemplateBundle bundle = templates.get(requested);
        ThrowUtils.throwIf(bundle == null, ErrorCode.PARAMS_ERROR, "未知模板");
        ThrowUtils.throwIf(!bundle.compatibleProfiles().contains(profileId),
                ErrorCode.PARAMS_ERROR, "模板与 StackProfile 不兼容");
        return bundle;
    }

    public TemplateBundle get(String id) {
        return id == null ? null : templates.get(id);
    }

    public List<TemplateBundle> list() {
        return List.copyOf(templates.values());
    }

    private void register(TemplateBundle source) {
        TemplateBundle withHash = new TemplateBundle(source.id(), source.version(),
                source.displayName(), source.compatibleProfiles(), source.sectionIds(),
                source.designTokens(), source.promptFragment(), hash(source));
        templates.put(withHash.id(), withHash);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> visualTokens(
            String primary,
            String accent,
            String background,
            String spacing,
            String grid,
            String radius,
            String typography,
            String imagery,
            String motion) {
        return Map.ofEntries(
                Map.entry("color.primary", primary),
                Map.entry("color.accent", accent),
                Map.entry("color.background", background),
                Map.entry("space.scale", spacing),
                Map.entry("grid", grid),
                Map.entry("radius.policy", radius),
                Map.entry("type.scale", typography),
                Map.entry("image.rhythm", imagery),
                Map.entry("motion.policy", motion));
    }

    private String hash(TemplateBundle bundle) {
        try {
            String content = bundle.id() + "|" + bundle.version() + "|"
                    + String.join(",", bundle.compatibleProfiles().stream().sorted().toList()) + "|"
                    + String.join(",", bundle.sectionIds()) + "|" + bundle.designTokens() + "|"
                    + bundle.promptFragment();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算模板摘要失败", e);
        }
    }
}
