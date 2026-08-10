package com.mofanyi.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建应用请求
 */
@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用描述
     */
    private String description;

    /**
     * 标签（逗号分隔）
     */
    private String tags;

    /**
     * 可见范围：public/private
     */
    private String visibility;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    private static final long serialVersionUID = 1L;
}
