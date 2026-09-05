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

    /**
     * 本次生成消耗的输入 token 数（透传模型 usage，供前端展示元信息）
     */
    private Long promptTokens;

    /**
     * 本次生成消耗的输出 token 数
     */
    private Long completionTokens;

    /**
     * 总 token 数（prompt + completion）
     */
    private Long totalTokens;

    private static final long serialVersionUID = 1L;
}
