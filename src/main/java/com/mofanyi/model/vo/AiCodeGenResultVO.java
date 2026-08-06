package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;

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

    private static final long serialVersionUID = 1L;
}
