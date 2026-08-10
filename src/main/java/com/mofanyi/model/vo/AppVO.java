package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用视图对象
 */
@Data
public class AppVO implements Serializable {

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
     * 生成状态：not_generated/generating/done/stopped/failed
     */
    private String generateStatus;

    /**
     * 精选状态：0未申请/1待审核/2已推荐/3已拒绝
     */
    private Integer featuredStatus;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 部署标识（非空表示已部署上线）
     */
    private String deployKey;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
