package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
public class VueBuildService {

    /**
     * 构建失败自动修复的最大重试次数
     */
    private static final int MAX_FIX_ATTEMPTS = 3;

    /**
     * 单条 npm 命令执行超时（毫秒）
     */
    private static final long EXEC_TIMEOUT_MS = 300_000;

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final CodeFileWriteService codeFileWriteService;
    private final SiteRepairAgent siteRepairAgent;
    private final ReasoningProfileRegistry reasoningProfiles;

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

    public VueBuildService(CodeFileWriteService codeFileWriteService,
                           SiteRepairAgent siteRepairAgent,
                           ReasoningProfileRegistry reasoningProfiles) {
        this.codeFileWriteService = codeFileWriteService;
        this.siteRepairAgent = siteRepairAgent;
        this.reasoningProfiles = reasoningProfiles;
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
        String projectDir = codeFileWriteService.getLiveProjectDir(CodeGenTypeEnum.VUE, appId);
        ThrowUtils.throwIf(!FileUtil.exist(Paths.get(projectDir).toFile()), ErrorCode.OPERATION_ERROR, "应用尚未生成代码，请先生成");

        notify(progress, "START_INSTALL");
        ExecResult install = exec(projectDir, "install");
        if (!install.success()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "npm install 失败：" + truncate(extractError(install.all())));
        }

        notify(progress, "START_BUILD");
        ExecResult build = exec(projectDir, "run build");
        for (int attempt = 0; attempt < MAX_FIX_ATTEMPTS && !build.success(); attempt++) {
            String error = extractError(build.all());
            notify(progress, "BUILD_FAILED|" + truncate(error, 300));
            // 修复 agent：根据错误日志覆盖出问题文件
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
            notify(progress, "REBUILDING");
            build = exec(projectDir, "run build");
        }
        if (!build.success()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "构建失败（已自动修复 " + MAX_FIX_ATTEMPTS + " 次）：" + truncate(extractError(build.stderr())));
        }
        notify(progress, "BUILD_OK");
        return Paths.get(projectDir, "dist").toFile();
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
    private ExecResult exec(String workDir, String args) {
        List<String> argTokens = args == null ? List.of() : List.of(args.trim().split("\\s+"));
        List<String> cmd = new ArrayList<>();
        if (StrUtil.isNotBlank(nodeCommand) && StrUtil.isNotBlank(npmCliPath)) {
            // node.exe <npm-cli.js> <args...>
            cmd.add(nodeCommand);
            cmd.add(npmCliPath);
            cmd.addAll(argTokens);
        } else if (IS_WINDOWS) {
            cmd.add("cmd");
            cmd.add("/c");
            cmd.add(StrUtil.isNotBlank(nodeCommand) ? nodeCommand : "npm.cmd");
            cmd.add(args);
        } else {
            cmd.add(StrUtil.isNotBlank(nodeCommand) ? nodeCommand : "npm");
            cmd.addAll(argTokens);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(false);
            Process process = pb.start();
            // 读 stdout / stderr（虚拟线程防管道阻塞，创建成本低，适合高并发）
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread outThread = Thread.ofVirtual().name("vue-build-out")
                    .start(() -> readAll(process.getInputStream(), stdout));
            Thread errThread = Thread.ofVirtual().name("vue-build-err")
                    .start(() -> readAll(process.getErrorStream(), stderr));
            boolean ok = process.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!ok) {
                process.destroyForcibly();
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "命令执行超时: npm " + args);
            }
            outThread.join(3000);
            errThread.join(3000);
            return new ExecResult(process.exitValue() == 0, stdout.toString(), stderr.toString());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "执行 npm " + args + " 失败: " + e.getMessage());
        }
    }

    /**
     * 读取流到 StringBuilder
     */
    private void readAll(InputStream in, StringBuilder sb) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        } catch (Exception ignore) {
            // 读取失败不影响主流程
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
    private record ExecResult(boolean success, String stdout, String stderr) {

        /**
         * 合并 stdout 与 stderr（错误可能输出到任意流）
         */
        String all() {
            String out = stdout == null ? "" : stdout;
            String err = stderr == null ? "" : stderr;
            return err.isBlank() ? out : out + "\n" + err;
        }
    }
}
