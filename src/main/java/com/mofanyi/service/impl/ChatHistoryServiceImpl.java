package com.mofanyi.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.App;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.ChatHistoryMapper;
import com.mofanyi.model.dto.chat.ChatHistoryQueryRequest;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.vo.ChatAppVO;
import com.mofanyi.service.ChatHistoryService;
import com.mybatisflex.core.constant.SqlOperator;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 服务层实现。
 *
 * @author 程序员luckymo
 */
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory>
        implements ChatHistoryService {

    /**
     * 默认每次加载的消息条数
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 用户侧单次加载的最大消息条数
     */
    private static final int MAX_PAGE_SIZE = 20;

    /**
     * 内部回灌读取的条数上限（防误传超大 limit）
     */
    private static final int MAX_REHYDRATE_SIZE = 100;

    private final AppMapper appMapper;

    public ChatHistoryServiceImpl(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Override
    public ChatHistory saveMessage(long appId, long userId, String message, String messageType, Long parentId) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR, "appId 不能为空");
        ThrowUtils.throwIf(userId <= 0, ErrorCode.PARAMS_ERROR, "userId 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        ThrowUtils.throwIf(ChatMessageTypeEnum.getEnumByValue(messageType) == null,
                ErrorCode.PARAMS_ERROR, "消息类型不合法");
        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        chatHistory.setMessage(message);
        chatHistory.setMessageType(messageType);
        chatHistory.setParentId(parentId);
        chatHistory.setCreateTime(LocalDateTime.now());
        chatHistory.setUpdateTime(LocalDateTime.now());
        ThrowUtils.throwIf(!this.save(chatHistory), ErrorCode.OPERATION_ERROR, "消息保存失败");
        return chatHistory;
    }

    @Override
    public boolean deleteByAppId(long appId) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        // 逻辑删除该应用下全部对话历史
        this.remove(QueryWrapper.create().eq("appId", appId));
        return true;
    }

    @Override
    public boolean physicalDeleteByAppId(long appId) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        // 物理删除该应用下全部对话历史，彻底清除数据
        LogicDeleteManager.execWithoutLogicDelete(
                () -> this.mapper.deleteByQuery(QueryWrapper.create().eq("appId", appId)));
        return true;
    }

    @Override
    public List<ChatHistory> listChatHistory(long appId, LocalDateTime beforeCreateTime, Long beforeId,
                                             int pageSize, User loginUser) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 权限：仅应用创建者和管理员可查看该应用的对话历史
        checkChatAccess(appId, loginUser);
        int size = pageSize > 0 ? Math.min(pageSize, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
        QueryWrapper queryWrapper = QueryWrapper.create().eq("appId", appId);
        // 游标：取 (createTime, id) 字典序早于 (beforeCreateTime, beforeId) 的最近 size 条。
        // 同一秒内可能存在多条消息，因此以创建时间为主游标、id 为同秒兜底，避免漏加载。
        if (beforeCreateTime != null) {
            // 注意：QueryCondition 的字符串操作符会原样拼接到 SQL，必须用 SqlOperator 枚举，
            // 传 "lt"/"eq" 会生成 createTime`lt'...' 这类非法 SQL
            QueryCondition timeLessCond = QueryCondition.create(new QueryColumn("createTime"), SqlOperator.LT, beforeCreateTime);
            if (beforeId != null && beforeId > 0) {
                QueryCondition timeEqCond = QueryCondition.create(new QueryColumn("createTime"), SqlOperator.EQUALS, beforeCreateTime);
                QueryCondition idLessCond = QueryCondition.create(new QueryColumn("id"), SqlOperator.LT, beforeId);
                queryWrapper.and(timeLessCond.or(timeEqCond.and(idLessCond)));
            } else {
                queryWrapper.and(timeLessCond);
            }
        }
        // 倒序取最新 size 条，再用 id 兜底保证稳定顺序
        queryWrapper.orderBy("createTime", false)
                .orderBy("id", false)
                .limit(size);
        List<ChatHistory> chatHistoryList = this.list(queryWrapper);
        // 倒序加载后翻转成正序，便于前端直接渲染
        CollUtil.reverse(chatHistoryList);
        return chatHistoryList;
    }

    @Override
    public List<ChatHistory> listRecentByAppId(long appId, int limit) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        int size = limit > 0 ? Math.min(limit, MAX_REHYDRATE_SIZE) : DEFAULT_PAGE_SIZE;
        // 倒序取最近 size 条（与 listChatHistory 同款稳定排序），再翻转成正序
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId)
                .orderBy("createTime", false)
                .orderBy("id", false)
                .limit(size);
        List<ChatHistory> chatHistoryList = this.list(queryWrapper);
        CollUtil.reverse(chatHistoryList);
        return chatHistoryList;
    }

    @Override
    public Page<ChatHistory> listAllByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = chatHistoryQueryRequest.getPageNum();
        long pageSize = chatHistoryQueryRequest.getPageSize();
        QueryWrapper queryWrapper = getQueryWrapper(chatHistoryQueryRequest);
        // 时间降序，便于内容监管
        queryWrapper.orderBy("createTime", false)
                .orderBy("id", false);
        return this.page(Page.of(pageNum, pageSize), queryWrapper);
    }

    @Override
    public Page<ChatAppVO> listChatAppByAdmin(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        QueryWrapper queryWrapper = QueryWrapper.create()
                .select("appId", "count(*) as messageCount", "max(createTime) as lastCreateTime")
                .from(ChatHistory.class)
                .groupBy("appId");
        // 按用户过滤：仅返回该用户参与过对话的应用
        Long userId = chatHistoryQueryRequest.getUserId();
        if (userId != null && userId > 0) {
            queryWrapper.and(QueryCondition.create(new QueryColumn("userId"), SqlOperator.EQUALS, userId));
        }
        // 最近有对话的应用排前面
        queryWrapper.orderBy("lastCreateTime", false);
        Page<ChatAppVO> chatAppVOPage = this.pageAs(Page.of(chatHistoryQueryRequest.getPageNum(),
                chatHistoryQueryRequest.getPageSize()), queryWrapper, ChatAppVO.class);
        fillAppInfo(chatAppVOPage.getRecords());
        return chatAppVOPage;
    }

    /**
     * 补充应用名与创建用户 id（从 app 表批量查询，避免逐条 N+1）
     */
    private void fillAppInfo(List<ChatAppVO> chatAppVOList) {
        if (CollUtil.isEmpty(chatAppVOList)) {
            return;
        }
        List<Long> appIds = chatAppVOList.stream()
                .map(ChatAppVO::getAppId)
                .filter(Objects::nonNull)
                .toList();
        if (CollUtil.isEmpty(appIds)) {
            return;
        }
        Map<Long, App> appMap = appMapper.selectListByIds(appIds).stream()
                .collect(Collectors.toMap(App::getId, app -> app, (a, b) -> a));
        for (ChatAppVO vo : chatAppVOList) {
            App app = appMap.get(vo.getAppId());
            if (app != null) {
                vo.setAppName(app.getAppName());
                vo.setUserId(app.getUserId());
            }
        }
    }

    /**
     * 校验对话历史访问权限：仅应用创建者和管理员可见
     */
    private void checkChatAccess(long appId, User loginUser) {
        if (isAdmin(loginUser)) {
            return;
        }
        // 直接经 mapper 查询应用归属，避免依赖 AppService 形成循环依赖
        App app = appMapper.selectOneByQuery(QueryWrapper.create().eq("id", appId));
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");
    }

    /**
     * 构建管理员查询条件
     */
    private QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest) {
        Long appId = chatHistoryQueryRequest.getAppId();
        Long userId = chatHistoryQueryRequest.getUserId();
        String messageType = chatHistoryQueryRequest.getMessageType();
        return QueryWrapper.create()
                .eq("appId", appId)
                .eq("userId", userId)
                .eq("messageType", messageType);
    }

    /**
     * 判断是否为管理员
     */
    private boolean isAdmin(User loginUser) {
        return loginUser != null && UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
    }
}
