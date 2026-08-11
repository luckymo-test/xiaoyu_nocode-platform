package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史按应用聚合视图对象（管理员视角，一个应用一行）
 */
@Data
public class ChatAppVO implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用创建用户 id
     */
    private Long userId;

    /**
     * 该应用的消息总数
     */
    private Long messageCount;

    /**
     * 最近一条消息的创建时间
     */
    private LocalDateTime lastCreateTime;

    private static final long serialVersionUID = 1L;
}
