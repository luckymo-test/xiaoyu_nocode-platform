package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        Path source = Paths.get(tmpRoot, codeGenType + "_" + appId);
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
        String dirName = type.getValue() + "_" + appId;
        Path dir = Paths.get(tmpRoot, dirName);
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
        String dirName = type.getValue() + "_" + appId;
        return Paths.get(tmpRoot, dirName).toString();
    }

    /**
     * 读取已生成的项目代码；未生成（index.html 不存在）时返回 null
     *
     * @param type  业务类型（html / multi_file）
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
        if (type == CodeGenTypeEnum.MULTI_FILE) {
            vo.setCssCode(readFile(projectDir, CSS_FILE));
            vo.setJsCode(readFile(projectDir, JS_FILE));
        }
        return vo;
    }

    /**
     * 保存单文件 HTML 生成结果：创建项目目录并写入 index.html
     *
     * @param result AI 生成的单文件 HTML 结果
     * @param appId  应用 id
     * @return 项目文件夹的 File 对象
     */
    public File saveHtmlCode(HtmlCodeResult result, Long appId) {
        ThrowUtils.throwIf(result == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        String projectDir = createProjectDir(CodeGenTypeEnum.HTML, appId);
        writeFile(projectDir, INDEX_HTML, result.getHtmlCode());
        return new File(projectDir);
    }

    /**
     * 保存多文件生成结果：创建项目目录并写入 index.html、css/styles.css、js/app.js
     *
     * @param result AI 生成的多文件结果
     * @param appId  应用 id
     * @return 项目文件夹的 File 对象
     */
    public File saveMultiFileCode(MultiFileCodeResult result, Long appId) {
        ThrowUtils.throwIf(result == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        String projectDir = createProjectDir(CodeGenTypeEnum.MULTI_FILE, appId);
        writeFile(projectDir, INDEX_HTML, result.getHtmlCode());
        writeFile(projectDir, CSS_FILE, result.getCssCode());
        writeFile(projectDir, JS_FILE, result.getJsCode());
        return new File(projectDir);
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
        Path file = Paths.get(projectDir, fileName);
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
        Path file = Paths.get(projectDir, fileName);
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
        return FileUtil.del(Paths.get(projectDir, fileName));
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
        // 删除临时目录
        Path tmpDir = Paths.get(tmpRoot, codeGenType + "_" + appId);
        FileUtil.del(tmpDir.toFile());
        // 删除部署目录
        if (StrUtil.isNotBlank(deployKey)) {
            Path deployDir = Paths.get(deployRoot, deployKey);
            FileUtil.del(deployDir.toFile());
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
        FileUtil.del(deployDir.toFile());
    }
}
