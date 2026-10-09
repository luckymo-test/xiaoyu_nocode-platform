package com.mofanyi.service;

import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.User;
import com.mofanyi.model.dto.chat.ChatHistoryQueryRequest;
import com.mofanyi.model.vo.ChatAppVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务层。
 *
 * @author 程序员luckymo
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 保存一条对话消息（用户消息 / AI 回复 / 错误信息）。
     * 返回保存后的实体（含自增 id），便于将用户消息 id 作为 AI 消息的父消息 id 进行上下文关联。
     *
     * @param appId       应用 id
     * @param userId      发送用户 id
     * @param message     消息内容
     * @param messageType 消息类型（ChatMessageTypeEnum：user / assistant / error）
     * @param parentId    父消息 id，可为 null
     * @return 保存后的对话消息
     */
    ChatHistory saveMessage(long appId, long userId, String message, String messageType, Long parentId);

    /**
     * 根据应用 id 批量逻辑删除对话历史（供删除应用时级联调用）。
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean deleteByAppId(long appId);

    /**
     * 根据应用 id 批量物理删除对话历史（供物理删除应用时级联调用，彻底清除数据）。
     *
     * @param appId 应用 id
     * @return 是否删除成功
     */
    boolean physicalDeleteByAppId(long appId);

    /**
     * 用户分页加载对话历史：每次返回最新 pageSize 条消息，支持向前加载更早记录。
     * 结果按时间正序返回，便于前端直接渲染。仅应用创建者和管理员可查看。
     *
     * @param appId           应用 id
     * @param beforeCreateTime 游标：当前已加载消息中最老一条的创建时间，向前加载更早记录；为空则加载最新消息
     * @param beforeId         兜底游标：与 beforeCreateTime 同一秒内的 id 下界，可为 null
     * @param pageSize         本次加载条数，默认 10，最大 20
     * @param loginUser        当前登录用户
     * @return 按时间正序的对话消息列表
     */
    List<ChatHistory> listChatHistory(long appId, LocalDateTime beforeCreateTime, Long beforeId, int pageSize, User loginUser);

    /**
     * 内部使用：按应用 id 取最近 N 条对话消息（按时间正序返回），供 Agent 记忆冷启动回灌。
     * 刻意不做权限校验——调用方是后台生成线程，没有登录用户上下文；数据范围由 appId 限定。
     *
     * @param appId 应用 id
     * @param limit 最多返回条数（内部会做上限保护）
     * @return 按时间正序的最近消息
     */
    List<ChatHistory> listRecentByAppId(long appId, int limit);

    /**
     * 管理员分页查询所有应用的对话历史（按时间降序，便于内容监管）。
     *
     * @param chatHistoryQueryRequest 查询请求（可按 appId / userId / messageType 过滤）
     * @return 分页对话历史
     */
    Page<ChatHistory> listAllByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 管理员分页查询"有对话历史的应用"聚合列表（一个应用一行）。
     * 含应用名、消息数、最近对话时间，可按 userId 过滤，按最近对话时间降序。
     *
     * @param chatHistoryQueryRequest 查询请求（可按 userId 过滤）
     * @return 分页的应用聚合视图
     */
    Page<ChatAppVO> listChatAppByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest);
}
