package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 代码文件写入工具服务。
 * 每个用户创建的代码生成项目在临时目录下新建一个文件夹（业务类型 + 雪花 ID 命名），
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
     * 创建一个新的项目文件夹，命名格式：{业务类型}_{雪花ID}
     *
     * @param type 业务类型（html / multi_file）
     * @return 项目文件夹的绝对路径
     */
    public String createProjectDir(CodeGenTypeEnum type) {
        String dirName = type.getValue() + "_" + IdUtil.getSnowflakeNextId();
        Path dir = Paths.get(tmpRoot, dirName);
        FileUtil.mkdir(dir.toFile());
        return dir.toString();
    }

    /**
     * 保存单文件 HTML 生成结果：创建项目目录并写入 index.html
     *
     * @param result AI 生成的单文件 HTML 结果
     * @return 项目文件夹的 File 对象
     */
    public File saveHtmlCode(HtmlCodeResult result) {
        String projectDir = createProjectDir(CodeGenTypeEnum.HTML);
        writeFile(projectDir, INDEX_HTML, result.getHtmlCode());
        return new File(projectDir);
    }

    /**
     * 保存多文件生成结果：创建项目目录并写入 index.html、css/styles.css、js/app.js
     *
     * @param result AI 生成的多文件结果
     * @return 项目文件夹的 File 对象
     */
    public File saveMultiFileCode(MultiFileCodeResult result) {
        String projectDir = createProjectDir(CodeGenTypeEnum.MULTI_FILE);
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
        return FileUtil.del(Paths.get(projectDir, fileName));
    }

    /**
     * 递归删除整个项目文件夹
     *
     * @param projectDir 项目文件夹绝对路径
     */
    public void deleteProjectDir(String projectDir) {
        FileUtil.del(Paths.get(projectDir));
    }
}
