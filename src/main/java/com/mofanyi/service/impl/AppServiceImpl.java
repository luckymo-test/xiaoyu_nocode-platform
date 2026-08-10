package com.mofanyi.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.dto.app.AppAddRequest;
import com.mofanyi.model.dto.app.AppQueryRequest;
import com.mofanyi.model.dto.app.AppUpdateRequest;
import com.mofanyi.model.enums.AppFeaturedStatusEnum;
import com.mofanyi.model.enums.AppGenerateStatusEnum;
import com.mofanyi.model.enums.AppVisibilityEnum;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.model.vo.AppVO;
import com.mofanyi.service.AppService;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 应用服务层实现。
 *
 * @author 程序员luckymo
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    /**
     * 用户侧列表每页最大条数
     */
    private static final int MAX_PAGE_SIZE = 20;

    /**
     * 部署标识字符集（大小写字母 + 数字）
     */
    private static final String DEPLOY_KEY_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * 部署标识长度
     */
    private static final int DEPLOY_KEY_LENGTH = 6;

    private final CodeFileWriteService codeFileWriteService;

    public AppServiceImpl(CodeFileWriteService codeFileWriteService) {
        this.codeFileWriteService = codeFileWriteService;
    }

    @Override
    public String deployApp(long appId, User loginUser) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppOwnership(app, loginUser);
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = generateUniqueDeployKey();
            App update = new App();
            update.setId(app.getId());
            update.setDeployKey(deployKey);
            ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        }
        codeFileWriteService.deployApp(app.getCodeGenType(), app.getId(), deployKey);
        // 更新部署时间
        App timeUpdate = new App();
        timeUpdate.setId(app.getId());
        timeUpdate.setDeployedTime(LocalDateTime.now());
        this.updateById(timeUpdate);
        return codeFileWriteService.getDomain() + "/" + deployKey;
    }

    @Override
    public boolean recoverApp(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 查询包含已删除的记录，且限定为当前登录用户的应用
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("userId", loginUser.getId());
        App deletedApp = LogicDeleteManager.execWithoutLogicDelete(() -> this.mapper.selectOneByQuery(queryWrapper));
        ThrowUtils.throwIf(deletedApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 已是正常状态则直接返回
        if (deletedApp.getIsDelete() == null || deletedApp.getIsDelete() == 0) {
            return true;
        }
        // 恢复：将 isDelete 置回 0
        App update = new App();
        update.setId(id);
        update.setIsDelete(0);
        boolean result = LogicDeleteManager.execWithoutLogicDelete(() -> this.updateById(update));
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public Page<AppVO> listRecycledAppVOByPage(AppQueryRequest appQueryRequest, User loginUser) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        checkPageSize(appQueryRequest);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        // 强制查询当前登录用户，且仅查已删除（isDelete=1）的记录
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest).eq("isDelete", 1);
        Page<App> appPage = LogicDeleteManager.execWithoutLogicDelete(
                () -> this.page(Page.of(pageNum, pageSize), queryWrapper));
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    @Override
    public boolean physicalDeleteApp(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        // 查询包含已删除的记录，且为当前登录用户的应用
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("userId", loginUser.getId());
        App app = LogicDeleteManager.execWithoutLogicDelete(() -> this.mapper.selectOneByQuery(queryWrapper));
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 删除生成/部署文件
        codeFileWriteService.deleteProjectFiles(app.getCodeGenType(), app.getId(), app.getDeployKey());
        // 物理删除数据库记录
        int rows = LogicDeleteManager.execWithoutLogicDelete(
                () -> this.mapper.deleteByQuery(QueryWrapper.create().eq("id", id)));
        ThrowUtils.throwIf(rows == 0, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean deleteAppBatch(List<Long> ids, User loginUser) {
        ThrowUtils.throwIf(CollUtil.isEmpty(ids), ErrorCode.PARAMS_ERROR);
        for (Long id : ids) {
            deleteApp(id, loginUser);
        }
        return true;
    }

    @Override
    public boolean recoverAppBatch(List<Long> ids, User loginUser) {
        ThrowUtils.throwIf(CollUtil.isEmpty(ids), ErrorCode.PARAMS_ERROR);
        for (Long id : ids) {
            recoverApp(id, loginUser);
        }
        return true;
    }

    @Override
    public boolean physicalDeleteAppBatch(List<Long> ids, User loginUser) {
        ThrowUtils.throwIf(CollUtil.isEmpty(ids), ErrorCode.PARAMS_ERROR);
        for (Long id : ids) {
            physicalDeleteApp(id, loginUser);
        }
        return true;
    }

    @Override
    public AiCodeGenResultVO getAppCode(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (type == null) {
            return null;
        }
        return codeFileWriteService.readProjectCode(type, app.getId());
    }

    @Override
    public void updateGenerateStatus(long id, String status) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(status), ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        App update = new App();
        update.setId(id);
        update.setGenerateStatus(status);
        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
    }

    @Override
    public boolean unDeployApp(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppOwnership(app, loginUser);
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            // 删除部署目录
            codeFileWriteService.deleteDeployDir(app.getDeployKey());
            // 清空部署标识与部署时间
            App update = new App();
            update.setId(id);
            update.setDeployKey(null);
            update.setDeployedTime(null);
            ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        }
        return true;
    }

    @Override
    public boolean applyFeatured(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppOwnership(app, loginUser);
        App update = new App();
        update.setId(id);
        update.setFeaturedStatus(AppFeaturedStatusEnum.PENDING.getValue());
        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean auditFeatured(long id, boolean approved) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        App update = new App();
        update.setId(id);
        update.setFeaturedStatus(approved ? AppFeaturedStatusEnum.APPROVED.getValue()
                : AppFeaturedStatusEnum.REJECTED.getValue());
        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean cancelFeatured(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        App update = new App();
        update.setId(id);
        update.setFeaturedStatus(AppFeaturedStatusEnum.NONE.getValue());
        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        return true;
    }

    /**
     * 生成不重复的 6 位部署标识
     */
    private String generateUniqueDeployKey() {
        SecureRandom random = new SecureRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(DEPLOY_KEY_LENGTH);
            for (int i = 0; i < DEPLOY_KEY_LENGTH; i++) {
                sb.append(DEPLOY_KEY_CHARS.charAt(random.nextInt(DEPLOY_KEY_CHARS.length())));
            }
            String key = sb.toString();
            QueryWrapper queryWrapper = new QueryWrapper();
            queryWrapper.eq("deployKey", key);
            long count = this.mapper.selectCountByQuery(queryWrapper);
            if (count == 0) {
                return key;
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成部署标识失败，请重试");
    }

    @Override
    public long addApp(AppAddRequest appAddRequest, User loginUser) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(appAddRequest.getInitPrompt()),
                ErrorCode.PARAMS_ERROR, "initPrompt 不能为空");
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 默认值：公开、未生成、未申请精选
        if (StrUtil.isBlank(app.getVisibility())) {
            app.setVisibility(AppVisibilityEnum.PUBLIC.getValue());
        }
        app.setGenerateStatus(AppGenerateStatusEnum.NOT_GENERATED.getValue());
        app.setFeaturedStatus(AppFeaturedStatusEnum.NONE.getValue());
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return app.getId();
    }

    @Override
    public boolean updateApp(AppUpdateRequest appUpdateRequest, User loginUser) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App oldApp = this.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppOwnership(oldApp, loginUser);
        // 用户侧允许修改：应用名称、封面、描述、标签、可见范围
        App app = new App();
        app.setId(oldApp.getId());
        app.setAppName(appUpdateRequest.getAppName());
        app.setCover(appUpdateRequest.getCover());
        app.setDescription(appUpdateRequest.getDescription());
        app.setTags(appUpdateRequest.getTags());
        app.setVisibility(appUpdateRequest.getVisibility());
        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean deleteApp(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App oldApp = this.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        checkAppOwnership(oldApp, loginUser);
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public AppVO getAppVOById(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 私有应用仅本人或管理员可查看
        if (AppVisibilityEnum.PRIVATE.getValue().equals(app.getVisibility())
                && !app.getUserId().equals(loginUser.getId())
                && !isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权查看该应用");
        }
        return getAppVO(app);
    }

    @Override
    public Page<AppVO> listPublicAppVOByPage(AppQueryRequest appQueryRequest, User loginUser) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        checkPageSize(appQueryRequest);
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest);
        // 普通用户只看公开应用；管理员可看全部
        if (!isAdmin(loginUser)) {
            queryWrapper.eq("visibility", AppVisibilityEnum.PUBLIC.getValue());
        }
        return pageAppVO(appQueryRequest, queryWrapper);
    }

    @Override
    public Page<AppVO> listFeaturedAppVOByPage(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        checkPageSize(appQueryRequest);
        QueryWrapper queryWrapper = getBaseWrapper(appQueryRequest);
        // 精选：已推荐且公开，且已部署上线
        queryWrapper.eq("featuredStatus", AppFeaturedStatusEnum.APPROVED.getValue());
        queryWrapper.eq("visibility", AppVisibilityEnum.PUBLIC.getValue());
        queryWrapper.isNotNull("deployKey");
        queryWrapper.orderBy("priority", false);
        queryWrapper.orderBy("createTime", false);
        return pageAppVO(appQueryRequest, queryWrapper);
    }

    @Override
    public Page<AppVO> listMyAppVOByPage(AppQueryRequest appQueryRequest, User loginUser) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        checkPageSize(appQueryRequest);
        // 强制查询当前登录用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest);
        return pageAppVO(appQueryRequest, queryWrapper);
    }

    @Override
    public boolean deleteAppByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App oldApp = this.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public boolean updateAppByAdmin(AppUpdateRequest appUpdateRequest) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        App oldApp = this.getById(appUpdateRequest.getId());
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        App app = new App();
        BeanUtil.copyProperties(appUpdateRequest, app);
        boolean result = this.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return true;
    }

    @Override
    public Page<App> listAppByPage(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest);
        return this.page(Page.of(pageNum, pageSize), queryWrapper);
    }

    @Override
    public App getAppById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        return app;
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        return appList.stream().map(this::getAppVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        QueryWrapper queryWrapper = getBaseWrapper(appQueryRequest);
        String sortField = appQueryRequest.getSortField();
        if (StrUtil.isNotBlank(sortField)) {
            queryWrapper.orderBy(sortField, "ascend".equals(appQueryRequest.getSortOrder()));
        }
        return queryWrapper;
    }

    /**
     * 校验用户侧列表分页大小不超过 20
     */
    private void checkPageSize(AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest.getPageSize() > MAX_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "每页最多 " + MAX_PAGE_SIZE + " 条");
    }

    /**
     * 分页查询并组装应用视图对象
     */
    private Page<AppVO> pageAppVO(AppQueryRequest appQueryRequest, QueryWrapper queryWrapper) {
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        Page<App> appPage = this.page(Page.of(pageNum, pageSize), queryWrapper);
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    /**
     * 校验应用归属：非创建用户无权操作
     */
    private void checkAppOwnership(App app, User loginUser) {
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "只能操作自己的应用");
    }

    /**
     * 构建基础查询条件（不含排序）
     */
    private QueryWrapper getBaseWrapper(AppQueryRequest appQueryRequest) {
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String tags = appQueryRequest.getTags();
        String visibility = appQueryRequest.getVisibility();
        Integer featuredStatus = appQueryRequest.getFeaturedStatus();
        String searchText = appQueryRequest.getSearchText();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .eq("visibility", visibility)
                .eq("featuredStatus", featuredStatus)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .like("tags", tags);
        // 多维度搜索：名称/描述/标签任一命中
        if (StrUtil.isNotBlank(searchText)) {
            QueryCondition nameCond = QueryCondition.create(new QueryColumn("appName"),
                    "like", "%" + searchText + "%");
            QueryCondition descCond = QueryCondition.create(new QueryColumn("description"),
                    "like", "%" + searchText + "%");
            QueryCondition tagsCond = QueryCondition.create(new QueryColumn("tags"),
                    "like", "%" + searchText + "%");
            queryWrapper.and(nameCond.or(descCond).or(tagsCond));
        }
        return queryWrapper;
    }

    /**
     * 判断是否为管理员
     */
    private boolean isAdmin(User loginUser) {
        return loginUser != null && UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
    }
}
