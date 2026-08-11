package com.mofanyi.controller;

import com.mofanyi.annotation.AuthCheck;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.chat.ChatHistoryQueryRequest;
import com.mofanyi.model.vo.ChatAppVO;
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制层。
 *
 * @author 程序员luckymo
 */
@RestController
@RequestMapping("/chatHistory")
public class ChatHistoryController {

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private UserService userService;

    /**
     * 用户加载对话历史：每次返回最新 pageSize 条消息，支持向前加载更早记录，结果按时间正序返回。
     * 仅应用创建者和管理员可查看。
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<List<ChatHistory>> listChatHistory(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest,
                                                           HttpServletRequest request) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null || chatHistoryQueryRequest.getAppId() == null
                || chatHistoryQueryRequest.getAppId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        List<ChatHistory> chatHistoryList = chatHistoryService.listChatHistory(
                chatHistoryQueryRequest.getAppId(),
                chatHistoryQueryRequest.getBeforeCreateTime(),
                chatHistoryQueryRequest.getBeforeId(),
                chatHistoryQueryRequest.getPageSize(),
                loginUser);
        return ResultUtils.success(chatHistoryList);
    }

    /**
     * 管理员分页查询所有应用的对话历史（按时间降序，便于内容监管）。
     */
    @PostMapping("/admin/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatHistory>> listAllByAdmin(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        Page<ChatHistory> chatHistoryPage = chatHistoryService.listAllByAdmin(chatHistoryQueryRequest);
        return ResultUtils.success(chatHistoryPage);
    }

    /**
     * 管理员分页查询"有对话历史的应用"聚合列表（一个应用一行），可按 userId 过滤。
     */
    @PostMapping("/admin/list/app/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ChatAppVO>> listChatAppByAdmin(@RequestBody ChatHistoryQueryRequest chatHistoryQueryRequest) {
        ThrowUtils.throwIf(chatHistoryQueryRequest == null, ErrorCode.PARAMS_ERROR);
        Page<ChatAppVO> chatAppVOPage = chatHistoryService.listChatAppByAdmin(chatHistoryQueryRequest);
        return ResultUtils.success(chatAppVOPage);
    }
}
