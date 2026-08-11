package com.mofanyi.model.dto.chat;

import com.mofanyi.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对话历史查询请求
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    /**
     * 用户 id（管理员按用户过滤）
     */
    private Long userId;

    /**
     * 消息类型：user / assistant / error
     */
    private String messageType;

    /**
     * 游标：当前已加载消息中最老一条的创建时间，用于向前加载更早记录；为空表示加载最新消息
     */
    private LocalDateTime beforeCreateTime;

    /**
     * 兜底游标：与 beforeCreateTime 同一秒内的 id 下界，保证同一秒多条消息不遗漏；可为空
     */
    private Long beforeId;

    private static final long serialVersionUID = 1L;
}
