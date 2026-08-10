package com.mofanyi.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 更新应用请求
 */
@Data
public class AppUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

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
     * 优先级（仅管理员可设置）
     */
    private Integer priority;

    private static final long serialVersionUID = 1L;
}
