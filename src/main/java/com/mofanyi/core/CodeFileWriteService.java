package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.workspace.ProjectWorkspaceContext;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 代码文件写入工具服务。
 * 每个用户创建的代码生成项目在临时目录下新建一个文件夹（业务类型 + 应用 ID 命名），
 * 文件夹内读写该项目的代码文件。
 * 临时目录根路径由配置文件 codegen.file.tmp-root 注入。
 */
@Service
@ConfigurationProperties(prefix = "codegen.file")
@Data
public class CodeFileWriteService {

    /**
     * 单文件模式的入口文件
     */
    private static final String INDEX_HTML = "index.html";
    /**
     * 多文件模式的样式文件
     */
    private static final String CSS_FILE = "css/styles.css";
    /**
     * 多文件模式的脚本文件
     */
    private static final String JS_FILE = "js/app.js";

    /**
     * 临时目录根路径
     */
    private String tmpRoot;

    /**
     * 部署目录根路径
     */
    private String deployRoot;

    /**
     * 部署域名
     */
    private String domain;

    @Autowired(required = false)
    private ProjectWorkspaceContext workspaceContext;

    /**
     * 部署应用：将临时目录下的项目复制到部署目录（deployKey 作为目录名）
     *
     * @param codeGenType 代码生成类型（html / multi_file）
     * @param appId       应用 id
     * @param deployKey   部署标识
     * @return 部署目录的 File 对象
     */
    public File deployApp(String codeGenType, Long appId, String deployKey) {
        ThrowUtils.throwIf(StrUtil.isBlank(codeGenType), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(deployKey), ErrorCode.PARAMS_ERROR);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(codeGenType);
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "未知生成类型");
        Path source = Paths.get(getLiveProjectDir(type, appId));
        ThrowUtils.throwIf(!FileUtil.exist(source.toFile()),
                ErrorCode.OPERATION_ERROR, "应用尚未生成代码，请先生成");
        Path target = Paths.get(deployRoot, deployKey);
        // 幂等：部署目录已存在则跳过复制
        if (!FileUtil.exist(target.toFile())) {
            FileUtil.mkdir(target.toFile());
            // 复制源目录内容到目标目录（而不是源目录本身作为子目录）
            FileUtil.copyContent(source.toFile(), target.toFile(), true);
        }
        return target.toFile();
    }

    /**
     * 创建一个新的项目文件夹，命名格式：{业务类型}_{应用ID}
     *
     * @param type  业务类型（html / multi_file）
     * @param appId 应用 id
     * @return 项目文件夹的绝对路径
     */
    public String createProjectDir(CodeGenTypeEnum type, Long appId) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        Path dir = projectPath(type, appId);
        FileUtil.mkdir(dir.toFile());
        return dir.toString();
    }

    /**
     * 获取项目文件夹的绝对路径（不创建目录）
     *
     * @param type  业务类型（html / multi_file）
     * @param appId 应用 id
     * @return 项目文件夹的绝对路径
     */
    public String getProjectDir(CodeGenTypeEnum type, Long appId) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        return projectPath(type, appId).toString();
    }

    /**
     * 始终返回 live 工程目录，绕过 Job 隔离上下文。
     */
    public String getLiveProjectDir(CodeGenTypeEnum type, Long appId) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        return liveProjectPath(type, appId).toString();
    }

    /**
     * 读取已生成的项目代码；未生成（index.html 不存在）时返回 null
     *
     * @param type  业务类型（html / multi_file / vue）
     * @param appId 应用 id
     * @return 生成结果 VO；未生成返回 null
     */
    public AiCodeGenResultVO readProjectCode(CodeGenTypeEnum type, Long appId) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        String projectDir = getProjectDir(type, appId);
        String htmlCode = readFile(projectDir, INDEX_HTML);
        if (htmlCode == null) {
            return null;
        }
        AiCodeGenResultVO vo = new AiCodeGenResultVO();
        vo.setProjectPath(projectDir);
        vo.setHtmlCode(htmlCode);
        // html / multi_file 都可能拆分了独立 css/js 文件，存在则一并返回
        if (type == CodeGenTypeEnum.MULTI_FILE || type == CodeGenTypeEnum.HTML) {
            vo.setCssCode(readFile(projectDir, CSS_FILE));
            vo.setJsCode(readFile(projectDir, JS_FILE));
        }
        if (type == CodeGenTypeEnum.VUE) {
            vo.setFiles(readVueProject(projectDir));
        }
        return vo;
    }

    /**
     * 外部预览/接口读取当前 live 工程，不读取 Job 尚未发布的草稿。
     */
    public AiCodeGenResultVO readLiveProjectCode(CodeGenTypeEnum type, Long appId) {
        return runLive(() -> readProjectCode(type, appId));
    }

    /**
     * 用户手动编辑始终作用于 live 当前版本。
     */
    public void saveLiveProjectFile(CodeGenTypeEnum type, Long appId, String path, String content) {
        runLive(() -> {
            saveProjectFile(type, appId, path, content);
            return null;
        });
    }

    public <T> T runWithLive(java.util.function.Supplier<T> action) {
        ThrowUtils.throwIf(action == null, ErrorCode.PARAMS_ERROR);
        return runLive(action);
    }

    /**
     * 读取 vue 工程全部文件（遍历项目目录，排除 node_modules / dist 构建产物），
     * 按路径字典序返回
     */
    public List<VueFileResult> readVueProject(String projectDir) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectDir), ErrorCode.PARAMS_ERROR);
        List<VueFileResult> files = new ArrayList<>();
        File root = new File(projectDir);
        if (!root.isDirectory()) {
            return files;
        }
        walkDir(root, root, files);
        files.sort(Comparator.comparing(VueFileResult::getPath));
        return files;
    }

    /**
     * 递归收集目录下所有文件（排除 node_modules / dist），路径为相对项目根路径
     */
    private void walkDir(File root, File dir, List<VueFileResult> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if ("node_modules".equals(child.getName()) || "dist".equals(child.getName())
                        || "out".equals(child.getName()) || ".next".equals(child.getName())
                        || ".agent".equals(child.getName())) {
                    continue;
                }
                walkDir(root, child, files);
            } else {
                VueFileResult f = new VueFileResult();
                f.setPath(relativePath(root, child));
                f.setContent(FileUtil.readString(child, StandardCharsets.UTF_8));
                files.add(f);
            }
        }
    }

    /**
     * 计算文件相对项目根的路径（用 / 分隔）
     */
    private String relativePath(File root, File file) {
        Path rootPath = root.toPath().toAbsolutePath();
        Path filePath = file.toPath().toAbsolutePath();
        String rel = rootPath.relativize(filePath).toString().replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    /**
     * 向 vue 工程目录写入单个文件（自动创建父目录）
     *
     * @param appId   应用 id
     * @param path    相对路径（如 src/App.js、package.json）
     * @param content 文件内容
     */
    public void saveVueFile(Long appId, String path, String content) {
        saveProjectFile(CodeGenTypeEnum.VUE, appId, path, content);
    }

    /**
     * 向任意类型项目目录写入单个文件（自动创建父目录）。
     * 通用 Agent 按相对路径写入 html/multi_file/vue 三种工程。
     *
     * @param type    代码生成类型
     * @param appId   应用 id
     * @param path    相对路径（如 index.html、css/styles.css、src/App.vue）
     * @param content 文件内容
     */
    public void saveProjectFile(CodeGenTypeEnum type, Long appId, String path, String content) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(path), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        String projectDir = getProjectDir(type, appId);
        writeFile(projectDir, path, content);
    }

    /**
     * 删除 vue 工程的构建产物目录（dist）。
     * vue 源码更新（重新生成）后调用，使下次预览/部署重新构建，避免预览旧页面。
     *
     * @param appId 应用 id
     */
    public void deleteVueDist(Long appId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        String projectDir = getProjectDir(CodeGenTypeEnum.VUE, appId);
        FileUtil.del(Paths.get(projectDir, "dist").toFile());
    }

    /**
     * 将内容写入项目文件夹下的指定文件，父目录不存在时自动创建
     *
     * @param projectDir 项目文件夹绝对路径
     * @param fileName   文件名（如 index.html、css/styles.css）
     * @param content    文件内容
     */
    public void writeFile(String projectDir, String fileName, String content) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectDir), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(fileName), ErrorCode.PARAMS_ERROR);
        Path file = ToolPaths.resolveWithin(Paths.get(projectDir), fileName);
        FileUtil.writeString(content, file.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * 读取项目文件夹下的指定文件内容
     *
     * @param projectDir 项目文件夹绝对路径
     * @param fileName   文件名
     * @return 文件内容；文件不存在时返回 null
     */
    public String readFile(String projectDir, String fileName) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectDir), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(fileName), ErrorCode.PARAMS_ERROR);
        Path file = ToolPaths.resolveWithin(Paths.get(projectDir), fileName);
        if (!FileUtil.exist(file.toFile())) {
            return null;
        }
        return FileUtil.readString(file.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * 删除项目文件夹下的指定文件
     *
     * @param projectDir 项目文件夹绝对路径
     * @param fileName   文件名
     * @return 删除成功返回 true，文件不存在返回 false
     */
    public boolean deleteFile(String projectDir, String fileName) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectDir), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(fileName), ErrorCode.PARAMS_ERROR);
        return FileUtil.del(ToolPaths.resolveWithin(Paths.get(projectDir), fileName));
    }

    /**
     * 递归删除整个项目文件夹
     *
     * @param projectDir 项目文件夹绝对路径
     */
    public void deleteProjectDir(String projectDir) {
        ThrowUtils.throwIf(StrUtil.isBlank(projectDir), ErrorCode.PARAMS_ERROR);
        FileUtil.del(Paths.get(projectDir));
    }

    /**
     * 删除应用相关的临时目录与部署目录（物理删除时清理文件）
     *
     * @param codeGenType 代码生成类型（html / multi_file）
     * @param appId       应用 id
     * @param deployKey   部署标识，可为空
     */
    public void deleteProjectFiles(String codeGenType, Long appId, String deployKey) {
        ThrowUtils.throwIf(StrUtil.isBlank(codeGenType), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        // 删除临时目录（不存在则跳过）
        Path tmpDir = Paths.get(tmpRoot, codeGenType + "_" + appId);
        if (FileUtil.exist(tmpDir.toFile())) {
            FileUtil.del(tmpDir.toFile());
        }
        // 删除部署目录（不存在则跳过）
        if (StrUtil.isNotBlank(deployKey)) {
            Path deployDir = Paths.get(deployRoot, deployKey);
            if (FileUtil.exist(deployDir.toFile())) {
                FileUtil.del(deployDir.toFile());
            }
        }
    }

    /**
     * 删除部署目录（取消部署/下线时清理，保留临时目录）
     *
     * @param deployKey 部署标识
     */
    public void deleteDeployDir(String deployKey) {
        ThrowUtils.throwIf(StrUtil.isBlank(deployKey), ErrorCode.PARAMS_ERROR);
        Path deployDir = Paths.get(deployRoot, deployKey);
        // 目录不存在时静默跳过，避免下线因部署目录缺失而失败
        if (FileUtil.exist(deployDir.toFile())) {
            FileUtil.del(deployDir.toFile());
        }
    }

    private Path projectPath(CodeGenTypeEnum type, Long appId) {
        if (workspaceContext != null) {
            Path override = workspaceContext.resolve(appId);
            if (override != null) {
                return override;
            }
        }
        return liveProjectPath(type, appId);
    }

    private Path liveProjectPath(CodeGenTypeEnum type, Long appId) {
        String dirName = type.getValue() + "_" + appId;
        return Paths.get(tmpRoot, dirName).toAbsolutePath().normalize();
    }

    private <T> T runLive(java.util.function.Supplier<T> action) {
        if (workspaceContext == null) {
            return action.get();
        }
        return workspaceContext.withLive(action);
    }
}
