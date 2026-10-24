package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.config.SandboxProperties;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Conditional;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue3 工程构建服务：执行 npm install + npm run build 产出 dist，构建失败时用修复 agent 自动修复重试。
 * 进度通过回调实时推给前端（供部署流式接口展示"正在构建/修复"状态）。
 */
@Slf4j
@Service
@Conditional(GenerationExecutionCondition.class)
public class VueBuildService {

    /**
     * 构建失败自动修复的最大重试次数
     */
    private static final int MAX_FIX_ATTEMPTS = 3;

    private static final int MAX_LOG_CHARS = 200_000;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final CodeFileWriteService codeFileWriteService;
    private final SiteRepairAgent siteRepairAgent;
    private final ReasoningProfileRegistry reasoningProfiles;
    private final SandboxProperties sandboxProperties;
    private final SandboxWorkspaceGuard sandboxGuard;
    private final ObjectMapper objectMapper;

    /**
     * node 可执行文件绝对路径；为空时用 PATH 里的 node。
     * 配合 npm 目录使用：node <npm-cli.js> <args>，绕开 npm.cmd 对 PATH 的依赖。
     */
    @Value("${codegen.node.command:}")
    private String nodeCommand;

    /**
     * npm-cli.js 绝对路径（node <该文件> <args> 等价于 npm <args>）；
     * 为空时回退用 npm.cmd。
     */
    @Value("${codegen.node.npmCli:}")
    private String npmCliPath;

    @Value("${codegen.node.use-explicit-cli:false}")
    private boolean useExplicitNodeCli;

    public VueBuildService(CodeFileWriteService codeFileWriteService,
                           SiteRepairAgent siteRepairAgent,
                           ReasoningProfileRegistry reasoningProfiles,
                           SandboxProperties sandboxProperties,
                           SandboxWorkspaceGuard sandboxGuard,
                           ObjectMapper objectMapper) {
        this.codeFileWriteService = codeFileWriteService;
        this.siteRepairAgent = siteRepairAgent;
        this.reasoningProfiles = reasoningProfiles;
        this.sandboxProperties = sandboxProperties;
        this.sandboxGuard = sandboxGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建进度回调：text 为文本标记，前端据此展示状态
     */
    public interface BuildProgress {
        void onProgress(String text);
    }

    /**
     * 构建 vue 工程：npm install → npm run build；失败用修复 agent 修复后重建，最多 N 次。
     *
     * @param appId    应用 id
     * @param progress 进度回调，可为 null
     * @return 构建产物目录（dist）
     */
    public File build(Long appId, BuildProgress progress) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        Path liveDir = Path.of(codeFileWriteService.getLiveProjectDir(
                CodeGenTypeEnum.VUE, appId)).toAbsolutePath().normalize();
        ThrowUtils.throwIf(!Files.isDirectory(liveDir), ErrorCode.OPERATION_ERROR,
                "应用尚未生成代码，请先生成");
        long startedAt = System.currentTimeMillis();
        Path sandbox = null;
        SandboxWorkspaceGuard.Stats sourceStats = null;
        SandboxWorkspaceGuard.Stats outputStats = null;
        ExecResult install = null;
        ExecResult build = null;
        String failureReason = null;
        try {
            sourceStats = sandboxGuard.validateSource(liveDir);
            sandbox = sandboxGuard.createBuildWorkspace(
                    codeFileWriteService.getTmpRoot(), appId);
            sandboxGuard.syncSource(liveDir, sandbox);

            notify(progress, "START_INSTALL");
            install = exec(sandbox, "install");
            if (!install.success()) {
                failureReason = install.timedOut()
                        ? "npm install 超时" : "npm install 失败：" + extractError(install.all());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, failureReason);
            }

            notify(progress, "START_BUILD");
            build = exec(sandbox, "run build");
            for (int attempt = 0; attempt < MAX_FIX_ATTEMPTS && !build.success(); attempt++) {
                String error = build.timedOut()
                        ? "构建命令超时" : extractError(build.all());
                notify(progress, "BUILD_FAILED|" + truncate(error, 300));
                try {
                    siteRepairAgent.fixBuildError(appId, error,
                            AiCallContext.of(appId, null, "fix"),
                            (path, size, content) -> notify(progress, "FIXED|" + path),
                            reasoningProfiles.defaultReasoningLevel());
                } catch (RuntimeException e) {
                    log.warn("Vue 自动修复失败, appId={}, attempt={}", appId, attempt + 1, e);
                    notify(progress, "FIX_FAILED|" + truncate(e.getMessage(), 200));
                    break;
                }
                sourceStats = sandboxGuard.validateSource(liveDir);
                sandboxGuard.syncSource(liveDir, sandbox);
                notify(progress, "REBUILDING");
                build = exec(sandbox, "run build");
            }
            if (!build.success()) {
                failureReason = build.timedOut() ? "构建命令超时"
                        : "构建失败（已自动修复 " + MAX_FIX_ATTEMPTS + " 次）："
                        + truncate(extractError(build.all()));
                throw new BusinessException(ErrorCode.OPERATION_ERROR, failureReason);
            }

            Path sandboxDist = sandbox.resolve("dist");
            outputStats = sandboxGuard.validateOutput(sandboxDist);
            sandboxGuard.replaceOutput(sandboxDist, liveDir.resolve("dist"));
            notify(progress, "BUILD_OK");
            writeBuildReport(appId, startedAt, true, null,
                    sourceStats, outputStats, install, build);
            return liveDir.resolve("dist").toFile();
        } catch (RuntimeException e) {
            if (failureReason == null) {
                failureReason = truncate(e.getMessage(), 1_000);
            }
            writeBuildReport(appId, startedAt, false, failureReason,
                    sourceStats, outputStats, install, build);
            throw e;
        } finally {
            sandboxGuard.cleanup(sandbox);
        }
    }

    /**
     * 读取构建产物并内联为自包含 HTML（供 iframe srcdoc 预览）。
     * 把 dist/index.html 里的 /assets/*.js 内联为 <script>、/assets/*.css 内联为 <style>，
     * 结果不依赖外网/CDN。未构建时返回 null。
     *
     * @param appId 应用 id
     * @return 自包含 HTML；dist 不存在返回 null
     */
    public String readVueDist(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        String projectDir = codeFileWriteService.getLiveProjectDir(CodeGenTypeEnum.VUE, appId);
        File distIndex = Paths.get(projectDir, "dist", "index.html").toFile();
        if (!FileUtil.exist(distIndex)) {
            return null;
        }
        String html = FileUtil.readString(distIndex, StandardCharsets.UTF_8);
        // 内联 <link rel="stylesheet" ... href="/assets/x.css">（兼容 ./assets/ 相对路径）
        Pattern cssPattern = Pattern.compile("<link[^>]*href=\"((?:\\./)?assets/[^\"]+\\.css)\"[^>]*>");
        Matcher cssMatcher = cssPattern.matcher(html);
        StringBuffer cssSb = new StringBuffer();
        while (cssMatcher.find()) {
            String assetPath = cssMatcher.group(1).replaceFirst("^\\./", "");
            File asset = Paths.get(projectDir, "dist", assetPath.replaceFirst("^/", "")).toFile();
            String content = FileUtil.exist(asset) ? FileUtil.readString(asset, StandardCharsets.UTF_8) : "";
            cssMatcher.appendReplacement(cssSb, Matcher.quoteReplacement("<style>" + content + "</style>"));
        }
        cssMatcher.appendTail(cssSb);
        html = cssSb.toString();
        // 内联 <script type="module" ... src="/assets/x.js">（兼容 ./assets/ 相对路径）
        Pattern jsPattern = Pattern.compile("<script[^>]*src=\"((?:\\./)?assets/[^\"]+\\.js)\"[^>]*></script>");
        Matcher jsMatcher = jsPattern.matcher(html);
        StringBuffer jsSb = new StringBuffer();
        while (jsMatcher.find()) {
            String assetPath = jsMatcher.group(1).replaceFirst("^\\./", "");
            File asset = Paths.get(projectDir, "dist", assetPath.replaceFirst("^/", "")).toFile();
            String content = FileUtil.exist(asset) ? FileUtil.readString(asset, StandardCharsets.UTF_8) : "";
            // 去掉 type="module" 的 crossorigin 属性，内联 module 脚本
            jsMatcher.appendReplacement(jsSb,
                    Matcher.quoteReplacement("<script type=\"module\">" + content + "</script>"));
        }
        jsMatcher.appendTail(jsSb);
        html = jsSb.toString();
        return html;
    }

    /**
     * 执行一条 npm 命令。
     * 优先 node.exe <npm-cli.js> <args...>（不依赖 PATH），否则回退 npm.cmd。
     * args 按空格拆成多个 token（如 "run build" → run + build）。
     */
    private ExecResult exec(Path workDir, String action) {
        List<String> argTokens = new ArrayList<>();
        if ("install".equals(action)) {
            argTokens.add("install");
            if (sandboxProperties.getBuild().isIgnoreScripts()) {
                argTokens.add("--ignore-scripts");
            }
            argTokens.add("--no-audit");
            argTokens.add("--no-fund");
            if (StrUtil.isNotBlank(sandboxProperties.getBuild().getRegistry())) {
                argTokens.add("--registry=" + sandboxProperties.getBuild().getRegistry());
            }
        } else {
            argTokens.add("run");
            argTokens.add("build");
        }
        List<String> cmd = new ArrayList<>();
        if (useExplicitNodeCli
                && StrUtil.isNotBlank(nodeCommand) && StrUtil.isNotBlank(npmCliPath)) {
            cmd.add(nodeCommand);
            cmd.add(npmCliPath);
            cmd.addAll(argTokens);
        } else if (IS_WINDOWS) {
            cmd.add("cmd.exe");
            cmd.add("/d");
            cmd.add("/s");
            cmd.add("/c");
            cmd.add("npm.cmd");
            cmd.addAll(argTokens);
        } else {
            cmd.add("npm");
            cmd.addAll(argTokens);
        }
        long timeoutMillis = Math.max(30,
                sandboxProperties.getCommandTimeoutSeconds()) * 1_000L;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            sanitizeEnvironment(pb.environment(), workDir);
            Process process = pb.start();
            BoundedOutput stdout = new BoundedOutput(MAX_LOG_CHARS);
            BoundedOutput stderr = new BoundedOutput(MAX_LOG_CHARS);
            Thread outThread = Thread.ofVirtual().name("vue-build-out")
                    .start(() -> readAll(process.getInputStream(), stdout));
            Thread errThread = Thread.ofVirtual().name("vue-build-err")
                    .start(() -> readAll(process.getErrorStream(), stderr));
            boolean ok = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!ok) {
                killTree(process);
                outThread.join(3000);
                errThread.join(3000);
                return new ExecResult(false, stdout.text(), stderr.text(), true);
            }
            outThread.join(3000);
            errThread.join(3000);
            return new ExecResult(process.exitValue() == 0,
                    stdout.text(), stderr.text(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "构建命令被中断：" + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "执行 npm " + action + " 失败: " + e.getMessage());
        }
    }

    /**
     * 读取流到 StringBuilder
     */
    private void readAll(InputStream in, BoundedOutput output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        } catch (Exception ignore) {
            // 读取失败不影响主流程
        }
    }

    private void sanitizeEnvironment(Map<String, String> environment, Path sandbox) {
        SandboxEnvironment.retainSafeKeys(environment);
        Path cache = sandbox.resolve(".npm-cache");
        Path temp = sandbox.resolve(".tmp");
        Path home = sandbox.resolve(".home");
        Path npmUserConfig = sandbox.resolve(".npmrc");
        try {
            Files.createDirectories(cache);
            Files.createDirectories(temp);
            Files.createDirectories(home);
            if (!Files.exists(npmUserConfig)) {
                Files.writeString(npmUserConfig, "", StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "创建 npm 隔离目录失败：" + e.getMessage());
        }
        environment.put("HOME", home.toString());
        environment.put("TEMP", temp.toString());
        environment.put("TMP", temp.toString());
        environment.put("TMPDIR", temp.toString());
        environment.put("NPM_CONFIG_CACHE", cache.toString());
        environment.put("NPM_CONFIG_USERCONFIG", npmUserConfig.toString());
        environment.put("NPM_CONFIG_REGISTRY", sandboxProperties.getBuild().getRegistry());
        environment.put("NPM_CONFIG_UPDATE_NOTIFIER", "false");
        environment.put("CI", "1");
    }

    private void killTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignore) {
                // 尽力回收后代进程
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 从构建日志提取关键错误信息：去掉 ANSI 颜色码，返回含错误关键字及文件/行号的上下文段落。
     * vite/rollup 的错误通常跨多行（首行 error during build、后续含文件与行号），需整体返回给修复 agent。
     */
    private String extractError(String output) {
        if (StrUtil.isBlank(output)) {
            return "未知构建错误";
        }
        // 去掉 ANSI 颜色码（[...m）
        String cleaned = output.replaceAll("\\u001b\\[[;\\d]*m", "");
        String[] lines = cleaned.split("\n");
        StringBuilder sb = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            String lower = line.toLowerCase();
            // 命中错误关键字，收集该行及其后若干行（含文件路径/行号/代码片段）
            if (lower.contains("error") || lower.contains("syntaxerror") || lower.contains("failed to resolve")
                    || lower.contains("cannot find") || lower.contains("missing") || lower.contains("not found")
                    || lower.contains("expected") || lower.contains("unexpected") || lower.contains("at "))
            {
                sb.append(line.trim()).append('\n');
                lineCount++;
                if (lineCount >= 15) {
                    break;
                }
            }
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            return truncate(cleaned, 800);
        }
        return truncate(result, 1200);
    }

    private void notify(BuildProgress progress, String text) {
        if (progress != null) {
            try {
                progress.onProgress(text);
            } catch (Exception ignore) {
                // 进度推送失败不影响构建
            }
        }
    }

    private void writeBuildReport(Long appId, long startedAt, boolean success,
                                  String failureReason,
                                  SandboxWorkspaceGuard.Stats sourceStats,
                                  SandboxWorkspaceGuard.Stats outputStats,
                                  ExecResult install, ExecResult build) {
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("generatedAt", LocalDateTime.now().toString());
            report.put("appId", String.valueOf(appId));
            report.put("success", success);
            report.put("failureReason", failureReason);
            report.put("durationMs", System.currentTimeMillis() - startedAt);
            report.put("sandboxEnabled", sandboxProperties.isEnabled());
            report.put("sourceFiles", sourceStats == null ? 0 : sourceStats.files());
            report.put("sourceBytes", sourceStats == null ? 0 : sourceStats.bytes());
            report.put("outputFiles", outputStats == null ? 0 : outputStats.files());
            report.put("outputBytes", outputStats == null ? 0 : outputStats.bytes());
            report.put("installTimedOut", install != null && install.timedOut());
            report.put("buildTimedOut", build != null && build.timedOut());
            Path out = Path.of(codeFileWriteService.getLiveProjectDir(
                    CodeGenTypeEnum.VUE, appId), ".agent", "build-report.json");
            Files.createDirectories(out.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(out.toFile(), report);
        } catch (Exception e) {
            log.warn("[build] 构建报告落盘失败，appId={}", appId, e);
        }
    }

    private String truncate(String s) {
        return truncate(s, 800);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 命令执行结果
     */
    private record ExecResult(boolean success, String stdout, String stderr,
                              boolean timedOut) {

        /**
         * 合并 stdout 与 stderr（错误可能输出到任意流）
         */
        String all() {
            String out = stdout == null ? "" : stdout;
            String err = stderr == null ? "" : stderr;
            return err.isBlank() ? out : out + "\n" + err;
        }
    }

    private static final class BoundedOutput {
        private final int maxChars;
        private final StringBuilder value;
        private boolean truncated;

        private BoundedOutput(int maxChars) {
            this.maxChars = maxChars;
            this.value = new StringBuilder(Math.min(maxChars, 16_384));
        }

        synchronized void append(String line) {
            if (truncated) {
                return;
            }
            int needed = (value.length() == 0 ? 0 : 1) + (line == null ? 0 : line.length());
            if (value.length() + needed > maxChars) {
                int remaining = maxChars - value.length();
                if (remaining > 1) {
                    if (value.length() > 0) {
                        value.append('\n');
                    }
                    value.append(StrUtil.maxLength(line, Math.max(0, remaining - 2)));
                }
                value.append("\n…(输出已截断)");
                truncated = true;
                return;
            }
            if (value.length() > 0) {
                value.append('\n');
            }
            value.append(line);
        }

        synchronized String text() {
            return value.toString();
        }
    }
}
