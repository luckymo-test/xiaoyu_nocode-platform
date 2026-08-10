package com.mofanyi.service;

import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.model.dto.app.AppAddRequest;
import com.mofanyi.model.dto.app.AppQueryRequest;
import com.mofanyi.model.dto.app.AppUpdateRequest;
import com.mofanyi.model.vo.AppVO;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 应用服务层。
 *
 * @author 程序员luckymo
 */
public interface AppService extends IService<App> {

    /**
     * 创建应用
     *
     * @param appAddRequest 创建请求
     * @param loginUser     登录用户
     * @return 新应用 id
     */
    long addApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 更新自己的应用（仅应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param loginUser        登录用户
     * @return 是否更新成功
     */
    boolean updateApp(AppUpdateRequest appUpdateRequest, User loginUser);

    /**
     * 删除自己的应用
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 是否删除成功
     */
    boolean deleteApp(long id, User loginUser);

    /**
     * 根据 id 获取应用详情（校验可见性）
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 应用视图对象
     */
    AppVO getAppVOById(long id, User loginUser);

    /**
     * 分页查询公开应用列表（可见范围控制，防爬虫限制每页条数）
     *
     * @param appQueryRequest 查询请求
     * @param loginUser       登录用户
     * @return 分页应用视图
     */
    Page<AppVO> listPublicAppVOByPage(AppQueryRequest appQueryRequest, User loginUser);

    /**
     * 分页查询自己的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param loginUser       登录用户
     * @return 分页应用视图
     */
    Page<AppVO> listMyAppVOByPage(AppQueryRequest appQueryRequest, User loginUser);

    /**
     * 分页查询精选应用列表（按优先级降序）
     *
     * @param appQueryRequest 查询请求
     * @return 分页应用视图
     */
    Page<AppVO> listFeaturedAppVOByPage(AppQueryRequest appQueryRequest);

    /**
     * 管理员根据 id 删除任意应用
     *
     * @param id 应用 id
     * @return 是否删除成功
     */
    boolean deleteAppByAdmin(long id);

    /**
     * 管理员更新任意应用（应用名称、封面、优先级）
     *
     * @param appUpdateRequest 更新请求
     * @return 是否更新成功
     */
    boolean updateAppByAdmin(AppUpdateRequest appUpdateRequest);

    /**
     * 管理员分页查询应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 分页应用
     */
    Page<App> listAppByPage(AppQueryRequest appQueryRequest);

    /**
     * 管理员根据 id 获取应用
     *
     * @param id 应用 id
     * @return 应用
     */
    App getAppById(long id);

    /**
     * 部署应用（仅本人可部署），返回可访问 URL
     *
     * @param appId     应用 id
     * @param loginUser 登录用户
     * @return 部署后的访问 URL
     */
    String deployApp(long appId, User loginUser);

    /**
     * 恢复自己误删的应用
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 是否恢复成功
     */
    boolean recoverApp(long id, User loginUser);

    /**
     * 分页查询自己已删除的应用列表（回收站）
     *
     * @param appQueryRequest 查询请求
     * @param loginUser       登录用户
     * @return 分页应用视图
     */
    Page<AppVO> listRecycledAppVOByPage(AppQueryRequest appQueryRequest, User loginUser);

    /**
     * 物理删除自己的应用（连同生成/部署文件）
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 是否删除成功
     */
    boolean physicalDeleteApp(long id, User loginUser);

    /**
     * 批量逻辑删除自己的应用
     *
     * @param ids       应用 id 集合
     * @param loginUser 登录用户
     * @return 是否删除成功
     */
    boolean deleteAppBatch(List<Long> ids, User loginUser);

    /**
     * 批量恢复自己误删的应用
     *
     * @param ids       应用 id 集合
     * @param loginUser 登录用户
     * @return 是否恢复成功
     */
    boolean recoverAppBatch(List<Long> ids, User loginUser);

    /**
     * 批量物理删除自己的应用（连同生成/部署文件）
     *
     * @param ids       应用 id 集合
     * @param loginUser 登录用户
     * @return 是否删除成功
     */
    boolean physicalDeleteAppBatch(List<Long> ids, User loginUser);

    /**
     * 更新应用生成状态
     *
     * @param id     应用 id
     * @param status 生成状态值（AppGenerateStatusEnum）
     */
    void updateGenerateStatus(long id, String status);

    /**
     * 取消部署（下线）自己的应用
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 是否成功
     */
    boolean unDeployApp(long id, User loginUser);

    /**
     * 申请精选推荐
     *
     * @param id        应用 id
     * @param loginUser 登录用户
     * @return 是否成功
     */
    boolean applyFeatured(long id, User loginUser);

    /**
     * 管理员审核精选申请
     *
     * @param id       应用 id
     * @param approved 是否通过
     * @return 是否成功
     */
    boolean auditFeatured(long id, boolean approved);

    /**
     * 管理员取消精选推荐
     *
     * @param id 应用 id
     * @return 是否成功
     */
    boolean cancelFeatured(long id);

    /**
     * 获取应用已生成的代码；未生成时返回 null
     *
     * @param id 应用 id
     * @return 生成结果 VO，未生成返回 null
     */
    AiCodeGenResultVO getAppCode(long id);

    /**
     * 转换为应用视图对象
     *
     * @param app 应用
     * @return 应用视图对象
     */
    AppVO getAppVO(App app);

    /**
     * 转换为应用视图对象列表
     *
     * @param appList 应用列表
     * @return 应用视图对象列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 获取查询条件
     *
     * @param appQueryRequest 查询请求
     * @return 查询条件
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);
}
