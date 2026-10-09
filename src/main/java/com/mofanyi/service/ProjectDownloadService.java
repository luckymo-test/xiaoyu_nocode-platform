package com.mofanyi.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileFilter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 代码下载服务：校验权限、定位源码目录、过滤后打包 zip 供前端下载。
 * 下载的是【生成目录】（源代码），而非部署目录（构建产物）。
 * 只有应用创建者能下载自己的代码。
 */
@Service
public class ProjectDownloadService {

    /**
     * 需要整体排除的目录（依赖 / 构建产物 / VCS / IDE）
     */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "dist", "build", "target", ".git", ".idea", ".vscode", "tmp", "__pycache__", ".agent");

    /**
     * 需要排除的具体文件（敏感配置 / 系统文件）
     */
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "application.yml", "application-location.yml", ".DS_Store", "Thumbs.db");

    /**
     * 按后缀排除的文件（本机配置 / 编译缓存 / 日志）
     */
    private static final List<String> EXCLUDED_SUFFIXES = List.of(
            ".local.yml", ".tsbuildinfo", ".class", ".log", ".pyc", ".iml");

    private final CodeFileWriteService codeFileWriteService;
    private final AppAuthService appAuthService;
    private final UserService userService;

    public ProjectDownloadService(CodeFileWriteService codeFileWriteService,
                                  AppAuthService appAuthService,
                                  UserService userService) {
        this.codeFileWriteService = codeFileWriteService;
        this.appAuthService = appAuthService;
        this.userService = userService;
    }

    /**
     * 下载信息：校验通过后返回，控制器据此组装响应头，再调用 {@link #writeZip} 写 zip
     */
    public record DownloadInfo(File sourceDir, FileFilter filter, String filename) {
    }

    /**
     * 校验下载权限并定位源码目录。
     *
     * @param appId   应用 id
     * @param request 请求（取登录用户）
     * @return 下载信息（源目录、过滤器、下载文件名）
     */
    public DownloadInfo validate(Long appId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        // 归属校验统一走 AppAuthService（plan-refactor 6.1）
        App app = appAuthService.requireOwner(appId, loginUser);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "应用类型未知");
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        File sourceDir = new File(projectDir);
        ThrowUtils.throwIf(!FileUtil.exist(sourceDir) || !sourceDir.isDirectory(),
                ErrorCode.OPERATION_ERROR, "应用尚未生成代码，请先生成");
        String filename = safeFileName(app.getAppName()) + "_" + type.getValue() + ".zip";
        return new DownloadInfo(sourceDir, this::isDownloadable, filename);
    }

    /**
     * 将过滤后的源码打包成 zip 写入 out。
     * withSrcDir=false：源目录本身不作为顶层目录，zip 根下直接是 index.html、css/、js/ 等。
     */
    public void writeZip(DownloadInfo info, OutputStream out) {
        ZipUtil.zip(out, StandardCharsets.UTF_8, false, info.filter(), info.sourceDir());
    }

    /**
     * 文件过滤器：目录命中排除集合则整目录跳过；文件按精确名 / 后缀排除
     */
    private boolean isDownloadable(File file) {
        String name = file.getName();
        if (file.isDirectory()) {
            return !EXCLUDED_DIRS.contains(name);
        }
        if (EXCLUDED_FILES.contains(name)) {
            return false;
        }
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 下载文件名去 Windows 非法字符（\ / : * ? " < > |）
     */
    private String safeFileName(String name) {
        String cleaned = (name == null ? "" : name.trim()).replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "app" : cleaned;
    }
}
