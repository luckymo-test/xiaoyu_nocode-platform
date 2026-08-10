package com.mofanyi.controller;

import cn.hutool.core.collection.CollUtil;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.DeleteRequest;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.annotation.AuthCheck;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.dto.app.AppAddRequest;
import com.mofanyi.model.dto.app.AppDeployRequest;
import com.mofanyi.model.dto.app.AppFeatureAuditRequest;
import com.mofanyi.model.dto.app.AppQueryRequest;
import com.mofanyi.model.dto.app.AppUpdateRequest;
import com.mofanyi.model.dto.app.DeleteBatchRequest;
import com.mofanyi.model.vo.AppVO;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppService;
import com.mofanyi.service.UserService;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用控制层。
 *
 * @author 程序员luckymo
 */
@RestController
@RequestMapping("/app")
public class AppController {

    @Autowired
    private AppService appService;

    @Autowired
    private UserService userService;

    /**
     * 创建应用
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest,
                                     HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long newAppId = appService.addApp(appAddRequest, loginUser);
        return ResultUtils.success(newAppId);
    }

    /**
     * 更新自己的应用（仅应用名称）
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.updateApp(appUpdateRequest, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 删除自己的应用
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.deleteApp(deleteRequest.getId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 恢复自己误删的应用
     */
    @PostMapping("/recover")
    public BaseResponse<Boolean> recoverApp(@RequestBody DeleteRequest deleteRequest,
                                            HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.recoverApp(deleteRequest.getId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 分页查询自己已删除的应用列表（回收站）
     */
    @PostMapping("/list/recycle/page/vo")
    public BaseResponse<Page<AppVO>> listRecycledAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                             HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Page<AppVO> appVOPage = appService.listRecycledAppVOByPage(appQueryRequest, loginUser);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 物理删除自己的应用（连同生成/部署文件）
     */
    @PostMapping("/physicalDelete")
    public BaseResponse<Boolean> physicalDeleteApp(@RequestBody DeleteRequest deleteRequest,
                                                   HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.physicalDeleteApp(deleteRequest.getId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 批量逻辑删除自己的应用
     */
    @PostMapping("/deleteBatch")
    public BaseResponse<Boolean> deleteAppBatch(@RequestBody DeleteBatchRequest deleteBatchRequest,
                                                HttpServletRequest request) {
        ThrowUtils.throwIf(deleteBatchRequest == null || CollUtil.isEmpty(
                deleteBatchRequest.getIds()), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.deleteAppBatch(deleteBatchRequest.getIds(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 批量恢复自己误删的应用
     */
    @PostMapping("/recoverBatch")
    public BaseResponse<Boolean> recoverAppBatch(@RequestBody DeleteBatchRequest deleteBatchRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(deleteBatchRequest == null || CollUtil.isEmpty(
                deleteBatchRequest.getIds()), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.recoverAppBatch(deleteBatchRequest.getIds(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 批量物理删除自己的应用（连同生成/部署文件）
     */
    @PostMapping("/physicalDeleteBatch")
    public BaseResponse<Boolean> physicalDeleteAppBatch(@RequestBody DeleteBatchRequest deleteBatchRequest,
                                                        HttpServletRequest request) {
        ThrowUtils.throwIf(deleteBatchRequest == null || CollUtil.isEmpty(
                deleteBatchRequest.getIds()), ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.physicalDeleteAppBatch(deleteBatchRequest.getIds(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取应用详情（校验可见性）
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        AppVO appVO = appService.getAppVOById(id, loginUser);
        return ResultUtils.success(appVO);
    }

    /**
     * 获取应用已生成的代码（未生成时 data 为 null）
     */
    @GetMapping("/get/code")
    public BaseResponse<AiCodeGenResultVO> getAppCode(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(request);
        AiCodeGenResultVO codeVO = appService.getAppCode(id);
        return ResultUtils.success(codeVO);
    }

    /**
     * 部署应用（仅本人可部署）
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest,
                                          HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null || appDeployRequest.getAppId() == null
                || appDeployRequest.getAppId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        String url = appService.deployApp(appDeployRequest.getAppId(), loginUser);
        return ResultUtils.success(url);
    }

    /**
     * 取消部署（下线）自己的应用
     */
    @PostMapping("/unDeploy")
    public BaseResponse<Boolean> unDeployApp(@RequestBody DeleteRequest deleteRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.unDeployApp(deleteRequest.getId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 分页查询自己的应用列表
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Page<AppVO> appVOPage = appService.listMyAppVOByPage(appQueryRequest, loginUser);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页查询精选应用列表（仅已推荐且公开，按优先级降序）
     */
    @PostMapping("/list/featured/page/vo")
    public BaseResponse<Page<AppVO>> listFeaturedAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                             HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        userService.getLoginUser(request);
        Page<AppVO> appVOPage = appService.listFeaturedAppVOByPage(appQueryRequest);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页查询公开应用列表（可见范围控制，支持多维度搜索）
     */
    @PostMapping("/list/public/page/vo")
    public BaseResponse<Page<AppVO>> listPublicAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                           HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Page<AppVO> appVOPage = appService.listPublicAppVOByPage(appQueryRequest, loginUser);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 申请精选推荐
     */
    @PostMapping("/feature/apply")
    public BaseResponse<Boolean> applyFeatured(@RequestBody DeleteRequest deleteRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = appService.applyFeatured(deleteRequest.getId(), loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 管理员审核精选申请
     */
    @PostMapping("/admin/feature/audit")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> auditFeatured(@RequestBody AppFeatureAuditRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0
                || request.getApproved() == null, ErrorCode.PARAMS_ERROR);
        boolean result = appService.auditFeatured(request.getId(), request.getApproved());
        return ResultUtils.success(result);
    }

    /**
     * 管理员取消精选推荐
     */
    @PostMapping("/admin/feature/cancel")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> cancelFeatured(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        boolean result = appService.cancelFeatured(deleteRequest.getId());
        return ResultUtils.success(result);
    }

    /**
     * 管理员删除任意应用
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        boolean result = appService.deleteAppByAdmin(deleteRequest.getId());
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新任意应用（应用名称、封面、优先级）
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppUpdateRequest appUpdateRequest) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        boolean result = appService.updateAppByAdmin(appUpdateRequest);
        return ResultUtils.success(result);
    }

    /**
     * 管理员分页查询应用列表
     */
    @PostMapping("/admin/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<App>> listAppByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        Page<App> appPage = appService.listAppByPage(appQueryRequest);
        return ResultUtils.success(appPage);
    }

    /**
     * 管理员根据 id 获取应用
     */
    @GetMapping("/admin/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<App> getAppById(long id) {
        App app = appService.getAppById(id);
        return ResultUtils.success(app);
    }

}
