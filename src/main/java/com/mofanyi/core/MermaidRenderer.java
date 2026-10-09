package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Mermaid 渲染：把 Mermaid 文本通过本地 mermaid-cli(mmdc) 渲染成 PNG。
 * - 依赖：node + @mermaid-js/mermaid-cli 的 src/cli.js；渲染依赖浏览器内核（Puppeteer），
 *   通过 puppeteer config 复用本机已装 Chrome/Edge（避免下载 Chromium）。
 * - 配置见 codegen.mermaid.*（node / mmdcCli / edgePath），缺省尝试用 codegen.node.command 的 node 与常见浏览器路径。
 * - 每次渲染新起进程（秒级），用信号量限制并发，避免多个 Puppeteer 实例相互冲突。
 */
@Slf4j
@Component
public class MermaidRenderer {

    private static final long RENDER_TIMEOUT_MS = 60_000;

    /**
     * 并发上限：Puppeteer/浏览器渲染较吃资源且并发易相互干扰
     */
    private final Semaphore semaphore = new Semaphore(2);

    @Value("${codegen.node.command:}")
    private String nodeCommand;

    @Value("${codegen.mermaid.node:}")
    private String mermaidNode;

    @Value("${codegen.mermaid.mmdcCli:}")
    private String mmdcCli;

    @Value("${codegen.mermaid.edgePath:}")
    private String edgePath;

    /**
     * 渲染 Mermaid 文本为 PNG 字节
     *
     * @param mermaidText Mermaid 语法文本
     * @return PNG 字节
     */
    public byte[] render(String mermaidText) {
        String node = StrUtil.isNotBlank(mermaidNode) ? mermaidNode : nodeCommand;
        if (StrUtil.isBlank(node)) {
            node = "node";
        }
        if (StrUtil.isBlank(mmdcCli)) {
            throw new IllegalStateException("未配置 codegen.mermaid.mmdcCli（mermaid-cli 的 src/cli.js 路径）");
        }
        try {
            semaphore.acquire();
            return doRender(node, mmdcCli, mermaidText);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("渲染被中断", e);
        } finally {
            semaphore.release();
        }
    }

    private byte[] doRender(String node, String cliJs, String mermaidText) {
        File workDir = FileUtil.file(FileUtil.getTmpDir(), "mmd-" + System.nanoTime());
        try {
            FileUtil.mkdir(workDir);
            File in = new File(workDir, "in.mmd");
            File out = new File(workDir, "out.png");
            File conf = new File(workDir, "puppeteer.json");
            FileUtil.writeString(mermaidText, in, StandardCharsets.UTF_8);
            String exec = StrUtil.isNotBlank(edgePath) && FileUtil.exist(edgePath)
                    ? edgePath
                    : "";
            // 沙箱加固（plan-agent-engineering 6.2）：Mermaid 文本来自模型，渲染在内置浏览器里跑。
            // 隔离用户数据目录 + 关掉后台网络/扩展/同步等非必要能力，减少被利用面。
            String profileDir = new File(workDir, "chrome-profile").getAbsolutePath().replace("\\", "/");
            String hardenArgs = "\"--no-sandbox\""
                    + ",\"--disable-extensions\""
                    + ",\"--disable-background-networking\""
                    + ",\"--disable-sync\""
                    + ",\"--disable-default-apps\""
                    + ",\"--no-first-run\""
                    + ",\"--disable-gpu\""
                    + ",\"--user-data-dir=" + profileDir + "\"";
            // executablePath 为空时不指定，让 puppeteer 自己找（需已安装对应内核）
            String confJson = exec.isEmpty()
                    ? "{\"args\":[" + hardenArgs + "]}"
                    : "{\"executablePath\":\"" + exec.replace("\\", "/") + "\",\"args\":[" + hardenArgs + "]}";
            FileUtil.writeString(confJson, conf, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(List.of(
                    node, cliJs, "-i", in.getAbsolutePath(),
                    "-o", out.getAbsolutePath(), "-p", conf.getAbsolutePath(),
                    // 安全（plan-agent-engineering 6.2）：Mermaid 文本来自模型，可能含 HTML/链接，
                    // 强制 strict 级别，禁止渲染期的脚本/交互能力
                    "-s", "strict"));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String logs = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = process.waitFor(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                process.destroyForcibly();
                throw new IllegalStateException("Mermaid 渲染超时");
            }
            if (process.exitValue() != 0 || !FileUtil.exist(out)) {
                log.warn("Mermaid 渲染失败 exit={} logs={}", process.exitValue(), StrUtil.maxLength(logs, 400));
                throw new IllegalStateException("Mermaid 渲染失败（请检查语法）: " + StrUtil.maxLength(logs, 300));
            }
            return FileUtil.readBytes(out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Mermaid 渲染异常: " + e.getMessage(), e);
        } finally {
            FileUtil.del(workDir);
        }
    }
}
