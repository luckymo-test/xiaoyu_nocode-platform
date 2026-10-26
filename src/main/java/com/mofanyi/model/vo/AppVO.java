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
     * 压缩图（封面缩略图 URL）
     */
    private String compressPictures;

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
     * 部署访问地址（已部署时由 domain + deployKey 拼出，供前端一键打开网站）
     */
    private String deployUrl;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;
    private String projectTemplateId;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    private Long userId;

    /**
     * 所属工作区 id
     */
    private Long workspaceId;

    /**
     * 当前发布版本 id
     */
    private Long currentRevision;

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
