package com.mofanyi.model.dto.app;

import com.mofanyi.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 应用查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AppQueryRequest extends PageRequest implements Serializable {

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
     * 精选状态：0未申请/1待审核/2已推荐/3已拒绝
     */
    private Integer featuredStatus;

    /**
     * 搜索关键词（按名称/描述/标签模糊搜索）
     */
    private String searchText;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * 部署标识
     */
    private String deployKey;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户id
     */
    private Long userId;

    private static final long serialVersionUID = 1L;
}
