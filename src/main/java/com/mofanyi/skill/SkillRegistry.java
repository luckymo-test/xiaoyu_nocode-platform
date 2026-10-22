package com.mofanyi.skill;

import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SkillRegistry {

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        register(new SkillDefinition(
                "seo-audit", "1", "SEO 基础审计",
                Set.of("html-static", "multi-file-static", "vue-vite", "next-static-export"),
                Set.of("readFile", "writeFile"), Set.of("content-read"),
                "为页面补齐语义化标题层级、meta description、Open Graph 和可抓取链接。",
                List.of("title 唯一", "meta description 非空", "主内容使用语义标签"), Set.of()));
        register(new SkillDefinition(
                "accessible-forms", "1", "可访问表单",
                Set.of("html-static", "multi-file-static", "vue-vite"),
                Set.of("readFile", "writeFile"), Set.of("content-read"),
                "所有输入必须有 label，错误提示与字段关联，键盘焦点清晰可见。",
                List.of("label 关联", "错误文本可读", "不依赖颜色传达状态"), Set.of()));
        register(new SkillDefinition(
                "gsap-motion", "1", "GSAP 动效",
                Set.of("vue-vite", "react-vite-ts", "next-static-export"),
                Set.of("readFile", "writeFile"), Set.of("dependency:gsap"),
                "仅使用受控依赖中的 gsap；优先 transform/opacity，尊重 prefers-reduced-motion。",
                List.of("减少动态偏好", "不阻塞首屏", "不制造横向溢出"), Set.of()));
        register(new SkillDefinition(
                "analytics-instrumentation", "1", "业务流量埋点",
                Set.of("html-static", "multi-file-static", "vue-vite", "next-static-export"),
                Set.of("readFile", "writeFile"), Set.of("analytics-write"),
                "只采集 page_view/session_start/cta_click 等低敏事件，不记录表单正文。",
                List.of("无个人敏感字段", "事件命名稳定", "失败不阻塞页面"), Set.of()));
    }

    public List<SkillDefinition> resolve(List<String> requestedIds, String profileId) {
        List<String> ids = requestedIds == null ? List.of() : requestedIds;
        LinkedHashSet<String> unique = new LinkedHashSet<>(ids);
        List<SkillDefinition> resolved = new ArrayList<>();
        for (String id : unique) {
            SkillDefinition skill = skills.get(id);
            ThrowUtils.throwIf(skill == null, ErrorCode.PARAMS_ERROR, "未知技能：" + id);
            ThrowUtils.throwIf(!skill.compatibleProfiles().contains(profileId),
                    ErrorCode.PARAMS_ERROR, "技能与 StackProfile 不兼容：" + id);
            for (SkillDefinition existing : resolved) {
                ThrowUtils.throwIf(existing.conflictsWith().contains(id)
                                || skill.conflictsWith().contains(existing.id()),
                        ErrorCode.PARAMS_ERROR, "技能冲突：" + existing.id() + " / " + id);
            }
            resolved.add(skill);
        }
        return List.copyOf(resolved);
    }

    public SkillDefinition get(String id) {
        return id == null ? null : skills.get(id);
    }

    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    private void register(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }
}
