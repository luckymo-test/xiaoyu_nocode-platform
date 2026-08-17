package com.mofanyi.model.vo;

import com.mofanyi.ai.model.VueFileResult;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI 代码生成结果视图对象
 */
@Data
public class AiCodeGenResultVO implements Serializable {

    /**
     * 项目文件夹路径
     */
    private String projectPath;

    /**
     * HTML 代码
     */
    private String htmlCode;

    /**
     * CSS 代码（单文件模式为空）
     */
    private String cssCode;

    /**
     * JS 代码（单文件模式为空）
     */
    private String jsCode;

    /**
     * 需求理解与实现方案描述
     */
    private String description;

    /**
     * Vue3 工程文件清单（vue 模式用；其他模式为空）
     */
    private List<VueFileResult> files;

    private static final long serialVersionUID = 1L;
}
