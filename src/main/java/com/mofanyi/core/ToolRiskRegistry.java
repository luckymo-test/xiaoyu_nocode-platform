package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * 工具风险分级（plan-agent-engineering 第四章）。
 *
 * <p>本项目的工具集里，**最危险的不是写文件，而是两个会对外发请求 / 起本地进程的工具**
 * （{@code searchImage} 出站请求、{@code diagram} 起 mmdc+puppeteer）。因此分级与约束按此设计：
 *
 * <table>
 *   <tr><th>工具</th><th>风险</th><th>约束</th></tr>
 *   <tr><td>readFile</td><td>低</td><td>仅相对路径（ToolPaths 校验）</td></tr>
 *   <tr><td>finish</td><td>低</td><td>无副作用</td></tr>
 *   <tr><td>writeFile</td><td>中</td><td>相对路径 + 单文件大小上限</td></tr>
 *   <tr><td>searchImage</td><td>高</td><td>出站域名白名单 + 超时 + 大小上限</td></tr>
 *   <tr><td>searchWeb</td><td>高</td><td>当前事实检索 + 限速/重试/缓存 + 不可信数据边界</td></tr>
 *   <tr><td>fetchWeb</td><td>高</td><td>仅公开 HTTP/HTTPS + URL 校验 + 正文截断</td></tr>
 *   <tr><td>diagram</td><td>高</td><td>长度上限 + strict 渲染 + 超时</td></tr>
 * </table>
 *
 * <p>"需人工审批"当前不做（本项目是全自动生成，无审批交互面），但风险表本身可观测、可扩展。
 */
@Component
public class ToolRiskRegistry {

    /**
     * 单文件写入内容上限（字符）：防止模型一次写入超大内容撑爆磁盘/上下文
     */
    private static final int MAX_WRITE_CHARS = 200_000;

    /**
     * 出站白名单：由各图源自己声明（{@link ImageSource#allowedHostSuffixes()}）后汇总。
     *
     * <p>从硬编码常量改成汇总，是为了消灭"加了图源忘了加白名单"这类漏接——现象是"接口能搜到、下载全失败"。
     */
    private final List<String> allowedHostSuffixes;

    public ToolRiskRegistry(List<ImageSource> imageSources) {
        this.allowedHostSuffixes = imageSources.stream()
                .flatMap(source -> source.allowedHostSuffixes().stream())
                .distinct()
                .toList();
    }

    public enum Risk {
        /** 只读，无副作用 */
        LOW,
        /** 写工程文件 */
        MEDIUM,
        /** 对外网络请求 / 起本地进程 */
        HIGH
    }

    public record ToolRisk(String tool, Risk risk, String constraint) {
    }

    private static final List<ToolRisk> RISKS = List.of(
            new ToolRisk("readFile", Risk.LOW, "仅相对路径（ToolPaths 校验）"),
            new ToolRisk("finish", Risk.LOW, "无副作用"),
            new ToolRisk("writeFile", Risk.MEDIUM, "相对路径 + 单文件 ≤ " + MAX_WRITE_CHARS + " 字符"),
            new ToolRisk("searchImage", Risk.HIGH, "出站域名白名单 + 超时 + 大小上限"),
            new ToolRisk("searchWeb", Risk.HIGH, "账号级 QPS + 重试 + 缓存 + 不可信外部数据"),
            new ToolRisk("fetchWeb", Risk.HIGH, "公开 HTTP/HTTPS URL + 正文截断 + 不可信外部数据"),
            new ToolRisk("diagram", Risk.HIGH, "mermaid 长度上限 + strict 渲染 + 超时"));

    /**
     * 风险表（供文档 / 可观测性展示）
     */
    public List<ToolRisk> risks() {
        return RISKS;
    }

    /**
     * writeFile 约束：内容大小上限
     */
    public void checkWriteContent(String tool, String content) {
        int length = content == null ? 0 : content.length();
        ThrowUtils.throwIf(length > MAX_WRITE_CHARS, ErrorCode.PARAMS_ERROR,
                (tool == null ? "writeFile" : tool) + " 内容过大（" + length + " 字符，上限 " + MAX_WRITE_CHARS + "）");
    }

    /**
     * searchImage 约束：出站地址必须在白名单内（防 SSRF——把平台当跳板探内网）
     */
    public void checkOutboundUrl(String url) {
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.PARAMS_ERROR, "出站地址为空");
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "出站地址非法：" + StrUtil.maxLength(url, 100));
        }
        ThrowUtils.throwIf(StrUtil.isBlank(host), ErrorCode.PARAMS_ERROR, "出站地址缺少主机名");
        boolean allowed = allowedHostSuffixes.stream()
                .anyMatch(suffix -> host.equalsIgnoreCase(suffix) || host.toLowerCase().endsWith("." + suffix));
        ThrowUtils.throwIf(!allowed, ErrorCode.PARAMS_ERROR,
                "出站地址不在允许的白名单内：" + host);
    }
}
