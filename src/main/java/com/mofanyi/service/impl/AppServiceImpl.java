package com.mofanyi.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.config.RedisChatMemoryRepository;
import com.mofanyi.constant.UserConstant;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.CoverImageService;
import com.mofanyi.core.VueBuildService;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppDeployment;
import com.mofanyi.entity.AppRuntimeSchema;
import com.mofanyi.entity.User;
import com.mofanyi.entity.WorkspaceAuditLog;
import com.mofanyi.exception.BusinessException;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppDeploymentMapper;
import com.mofanyi.mapper.WorkspaceAuditLogMapper;
import com.mofanyi.mapper.AppRuntimeSchemaMapper;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.mapper.AppImageMapper;
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
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.WorkspaceService;
import com.mofanyi.workspace.WorkspaceAclService;
import com.mofanyi.workspace.WorkspacePermission;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.constant.SqlOperator;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private final ChatHistoryService chatHistoryService;

    private final RedisChatMemoryRepository redisChatMemoryRepository;

    private final ObjectProvider<VueBuildService> vueBuildServiceProvider;

    private final CoverImageService coverImageService;

    private final AppImageMapper appImageMapper;
    private final AppAuthService appAuthService;
    private final WorkspaceService workspaceService;
    private final WorkspaceAclService workspaceAclService;
    private final AppDeploymentMapper deploymentMapper;
    private final WorkspaceAuditLogMapper auditMapper;
    private final AppRuntimeSchemaMapper runtimeSchemaMapper;

    public AppServiceImpl(CodeFileWriteService codeFileWriteService,
                          ChatHistoryService chatHistoryService,
                          RedisChatMemoryRepository redisChatMemoryRepository,
                          ObjectProvider<VueBuildService> vueBuildServiceProvider,
                          CoverImageService coverImageService,
                          AppImageMapper appImageMapper,
                          AppAuthService appAuthService,
                          WorkspaceService workspaceService,
                          WorkspaceAclService workspaceAclService,
                          AppDeploymentMapper deploymentMapper,
                          WorkspaceAuditLogMapper auditMapper,
                          AppRuntimeSchemaMapper runtimeSchemaMapper) {
        this.codeFileWriteService = codeFileWriteService;
        this.chatHistoryService = chatHistoryService;
        this.redisChatMemoryRepository = redisChatMemoryRepository;
        this.vueBuildServiceProvider = vueBuildServiceProvider;
        this.coverImageService = coverImageService;
        this.appImageMapper = appImageMapper;
        this.appAuthService = appAuthService;
        this.workspaceService = workspaceService;
        this.workspaceAclService = workspaceAclService;
        this.deploymentMapper = deploymentMapper;
        this.auditMapper = auditMapper;
        this.runtimeSchemaMapper = runtimeSchemaMapper;
    }

    @Override
    public String deployApp(long appId, User loginUser) {
        return doDeploy(appId, loginUser, null);
    }

    @Override
    public String deployAppStream(long appId, User loginUser, java.util.function.Consumer<String> progress) {
        return doDeploy(appId, loginUser, progress);
    }

    /**
     * 部署应用：vue 模式先构建（含自动修复）再拷 dist，其他模式整目录拷贝。
     * progress 为流式部署的进度回调，可为 null（普通部署）。
     */
    private String doDeploy(long appId, User loginUser, java.util.function.Consumer<String> progress) {
        ThrowUtils.throwIf(appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        appAuthService.requireCanPublish(app.getId(), loginUser);
        String sourceDir;
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = generateUniqueDeployKey();
            App update = new App();
            update.setId(app.getId());
            update.setDeployKey(deployKey);
            ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR);
        }
        if (CodeGenTypeEnum.VUE.getValue().equals(app.getCodeGenType())) {
            java.io.File distDir = requireVueBuildService().build(app.getId(),
                    text -> { if (progress != null) progress.accept(text); });
            sourceDir = distDir.getAbsolutePath();
        } else {
            sourceDir = codeFileWriteService.getLiveProjectDir(
                    CodeGenTypeEnum.getEnumByValue(app.getCodeGenType()), app.getId());
        }
        Path target = Path.of(deployRoot(), deployKey).toAbsolutePath().normalize();
        Path stage = Path.of(deployRoot(), ".stage-" + deployKey + "-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Path backup = Path.of(deployRoot(), ".backup-" + deployKey + "-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        ThrowUtils.throwIf(!target.startsWith(Path.of(deployRoot()).toAbsolutePath().normalize()),
                ErrorCode.OPERATION_ERROR, "部署路径越界");
        try {
            cn.hutool.core.io.FileUtil.del(stage.toFile());
            cn.hutool.core.io.FileUtil.mkdir(stage.toFile());
            cn.hutool.core.io.FileUtil.copyContent(new java.io.File(sourceDir), stage.toFile(), true);
            if (Files.exists(target)) {
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveDeployDir(stage, target);
            cn.hutool.core.io.FileUtil.del(backup.toFile());
        } catch (RuntimeException | java.io.IOException e) {
            if (!Files.exists(target) && Files.exists(backup)) {
                try {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException restoreError) {
                    e.addSuppressed(restoreError);
                }
            }
            throw new IllegalStateException("发布目录切换失败", e);
        }
        // 更新部署时间
        App timeUpdate = new App();
        timeUpdate.setId(app.getId());
        timeUpdate.setDeployedTime(LocalDateTime.now());
        this.updateById(timeUpdate);
        deactivatePreviousDeployments(app.getId());
        String deployUrl = codeFileWriteService.getDomain() + "/" + deployKey;
        deploymentMapper.insertSelective(AppDeployment.builder()
                .workspaceId(app.getWorkspaceId())
                .appId(app.getId())
                .revisionId(app.getCurrentRevision() == null ? 0L : app.getCurrentRevision())
                .deployKey(deployKey)
                .url(deployUrl)
                .status("ACTIVE")
                .changeSummary("发布当前 Revision")
                .createdBy(loginUser.getId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
        audit(app, loginUser.getId(), "APP_PUBLISH", deployUrl);
        // 异步生成封面截图：失败仅记日志，不影响部署
        coverImageService.generateCoverAsync(app.getId(), deployUrl);
        return deployUrl;
    }

    /**
     * 部署目录根路径（复用 CodeFileWriteService 的 deployRoot 配置）
     */
    private String deployRoot() {
        return codeFileWriteService.getDeployRoot();
    }

    private VueBuildService requireVueBuildService() {
        VueBuildService service = vueBuildServiceProvider.getIfAvailable();
        ThrowUtils.throwIf(service == null, ErrorCode.OPERATION_ERROR,
                "当前控制面不执行 npm；Vue 构建/预览请使用 Generation Worker 或 Execution Worker");
        return service;
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
        // 级联物理删除该应用的全部对话历史，彻底清除数据
        chatHistoryService.physicalDeleteByAppId(id);
        // 级联物理删除该应用的嵌入图片记录
        appImageMapper.deleteByAppId(id);
        // 清理 Redis 中的对话记忆
        redisChatMemoryRepository.deleteByConversationId(String.valueOf(id));
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
        return codeFileWriteService.readLiveProjectCode(type, app.getId());
    }

    @Override
    public String getVuePreview(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!CodeGenTypeEnum.VUE.getValue().equals(app.getCodeGenType()),
                ErrorCode.PARAMS_ERROR, "非前端工程无法预览构建产物");
        VueBuildService vueBuildService = vueBuildServiceProvider.getIfAvailable();
        String html = vueBuildService == null
                ? codeFileWriteService.readFile(
                java.nio.file.Path.of(codeFileWriteService.getLiveProjectDir(
                        CodeGenTypeEnum.VUE, app.getId()), "dist").toString(), "index.html")
                : vueBuildService.readVueDist(app.getId());
        if (html == null && vueBuildService != null) {
            synchronized (String.valueOf(app.getId()).intern()) {
                html = vueBuildService.readVueDist(app.getId());
                if (html == null) {
                    vueBuildService.build(app.getId(), null);
                    html = vueBuildService.readVueDist(app.getId());
                }
            }
        }
        ThrowUtils.throwIf(html == null, ErrorCode.OPERATION_ERROR, "前端工程构建失败，无法预览");
        return html;
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
    public int failStaleGenerating() {
        // 有持久化 Job 的任务可能在其它实例继续运行，不能再按“启动时本地任务表为空”批量误杀。
        return this.mapper.failStaleGeneratingWithoutActiveJob();
    }

    @Override
    public boolean unDeployApp(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        App app = this.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        appAuthService.requireCanPublish(app.getId(), loginUser);
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            codeFileWriteService.deleteDeployDir(app.getDeployKey());
            int rows = this.mapper.clearDeployedTime(app.getId());
            ThrowUtils.throwIf(rows <= 0, ErrorCode.OPERATION_ERROR);
            deactivatePreviousDeployments(app.getId());
            audit(app, loginUser.getId(), "APP_UNPUBLISH", app.getDeployKey());
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

    private void moveDeployDir(Path source, Path target) throws java.io.IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void deactivatePreviousDeployments(Long appId) {
        for (AppDeployment deployment : deploymentMapper.selectListByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("status", "ACTIVE"))) {
            deployment.setStatus("INACTIVE");
            deployment.setUpdateTime(LocalDateTime.now());
            deploymentMapper.update(deployment);
        }
    }

    private void audit(App app, Long actorId, String action, String detail) {
        if (app.getWorkspaceId() == null) {
            return;
        }
        auditMapper.insertSelective(WorkspaceAuditLog.builder()
                .workspaceId(app.getWorkspaceId())
                .actorId(actorId)
                .action(action)
                .resourceType("APP")
                .resourceId(String.valueOf(app.getId()))
                .detail(detail)
                .createTime(LocalDateTime.now())
                .build());
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
        app.setWorkspaceId(workspaceService.ensureDefaultWorkspace(loginUser).getId());
        // 默认值：公开、未生成、未申请精选
        if (StrUtil.isBlank(app.getVisibility())) {
            app.setVisibility(AppVisibilityEnum.PRIVATE.getValue());
        }
        app.setGenerateStatus(AppGenerateStatusEnum.NOT_GENERATED.getValue());
        app.setFeaturedStatus(AppFeaturedStatusEnum.NONE.getValue());
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        audit(app, loginUser.getId(), "APP_CREATE", app.getAppName());
        return app.getId();
    }

    @Override
    public long copyApp(long sourceAppId, User loginUser) {
        App source = appAuthService.requireCanGenerate(sourceAppId, loginUser);
        String suffix = " 副本";
        String baseName = StrUtil.blankToDefault(source.getAppName(), "未命名应用");
        String copyName = StrUtil.maxLength(baseName, 50 - suffix.length()) + suffix;
        LocalDateTime now = LocalDateTime.now();
        App copy = App.builder()
                .appName(copyName)
                .cover(source.getCover())
                .compressPictures(source.getCompressPictures())
                .description(source.getDescription())
                .tags(source.getTags())
                .visibility(AppVisibilityEnum.PRIVATE.getValue())
                .generateStatus(source.getGenerateStatus())
                .featuredStatus(AppFeaturedStatusEnum.NONE.getValue())
                .initPrompt(source.getInitPrompt())
                .codeGenType(source.getCodeGenType())
                .priority(0)
                .userId(loginUser.getId())
                .workspaceId(workspaceService.ensureDefaultWorkspace(loginUser).getId())
                .editTime(now)
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
        ThrowUtils.throwIf(!save(copy), ErrorCode.OPERATION_ERROR, "创建项目副本失败");
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(source.getCodeGenType());
        if (type != null) {
            java.io.File from = new java.io.File(codeFileWriteService.getLiveProjectDir(type, sourceAppId));
            if (from.isDirectory()) {
                java.io.File to = new java.io.File(codeFileWriteService.getLiveProjectDir(type, copy.getId()));
                cn.hutool.core.io.FileUtil.mkdir(to);
                cn.hutool.core.io.FileUtil.copyContent(from, to, true);
            }
        }
        AppRuntimeSchema sourceSchema = runtimeSchemaMapper.selectOneByQuery(
                QueryWrapper.create()
                        .eq("appId", sourceAppId)
                        .orderBy("schemaVersion", false)
                        .limit(1));
        if (sourceSchema != null) {
            runtimeSchemaMapper.insertSelective(AppRuntimeSchema.builder()
                    .workspaceId(copy.getWorkspaceId())
                    .appId(copy.getId())
                    .revisionId(0L)
                    .schemaVersion(1)
                    .schemaJson(sourceSchema.getSchemaJson())
                    .sha256(sourceSchema.getSha256())
                    .createTime(now)
                    .build());
        }
        audit(copy, loginUser.getId(), "APP_COPY", "source=" + sourceAppId);
        return copy.getId();
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
        audit(oldApp, loginUser.getId(), "APP_UPDATE", app.getAppName());
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
        audit(oldApp, loginUser.getId(), "APP_DELETE", "logical");
        // 级联逻辑删除该应用的全部对话历史与嵌入图片
        chatHistoryService.deleteByAppId(id);
        appImageMapper.deleteByAppId(id);
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
                && !isAdmin(loginUser)) {
            if (!app.getUserId().equals(loginUser.getId())) {
                ThrowUtils.throwIf(app.getWorkspaceId() == null, ErrorCode.NO_AUTH_ERROR,
                        "无权查看该应用");
                workspaceAclService.require(app.getWorkspaceId(), loginUser.getId(),
                        WorkspacePermission.VIEW);
            }
        }
        return getAppVO(app);
    }

    @Override
    public Page<AppVO> listPublicAppVOByPage(AppQueryRequest appQueryRequest, User loginUser) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        checkPageSize(appQueryRequest);
        QueryWrapper queryWrapper = getQueryWrapper(appQueryRequest);
        // 公开应用栏对所有用户（含管理员）只展示公开应用，私有应用不在此栏目显示
        queryWrapper.eq("visibility", AppVisibilityEnum.PUBLIC.getValue());
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
        queryWrapper.isNotNull("deployedTime");
        queryWrapper.orderBy("priority", false);
        queryWrapper.orderBy("createTime", false);
        return pageAppVO(appQueryRequest, queryWrapper);
    }

    @Override
    public Page<AppVO> listMyAppVOByPage(AppQueryRequest appQueryRequest, User loginUser) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        checkPageSize(appQueryRequest);
        appQueryRequest.setUserId(loginUser.getId());
        List<App> own = this.list(getQueryWrapper(appQueryRequest));
        List<Long> workspaceIds = workspaceService.listWorkspaceIds(loginUser.getId());
        List<App> shared = workspaceIds.isEmpty() ? List.of()
                : this.list(QueryWrapper.create()
                .in("workspaceId", workspaceIds)
                .ne("userId", loginUser.getId()));
        java.util.LinkedHashMap<Long, App> merged = new java.util.LinkedHashMap<>();
        own.forEach(app -> merged.put(app.getId(), app));
        shared.forEach(app -> merged.putIfAbsent(app.getId(), app));
        List<App> sorted = merged.values().stream()
                .filter(app -> StrUtil.isBlank(appQueryRequest.getAppName())
                        || StrUtil.containsIgnoreCase(app.getAppName(),
                        appQueryRequest.getAppName()))
                .sorted(java.util.Comparator.comparing(
                        (App app) -> app.getEditTime() == null
                                ? app.getUpdateTime() : app.getEditTime(),
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
        long pageSize = appQueryRequest.getPageSize();
        long pageNum = appQueryRequest.getPageNum();
        int from = (int) Math.min(sorted.size(), Math.max(0, (pageNum - 1) * pageSize));
        int to = (int) Math.min(sorted.size(), from + pageSize);
        Page<AppVO> result = new Page<>(pageNum, pageSize, sorted.size());
        result.setRecords(getAppVOList(sorted.subList(from, to)));
        return result;
    }

    @Override
    public boolean deleteAppByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        App oldApp = this.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 级联逻辑删除该应用的全部对话历史与嵌入图片
        chatHistoryService.deleteByAppId(id);
        appImageMapper.deleteByAppId(id);
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
        // 已部署应用拼出访问地址，供前端"打开网站"按钮使用
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            appVO.setDeployUrl(codeFileWriteService.getDomain() + "/" + app.getDeployKey());
        }
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
        // 统一走 AppAuthService，避免同一条规则在多处各写一份（plan-refactor 6.1）
        appAuthService.requireOwner(app, loginUser);
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
                    SqlOperator.LIKE, "%" + searchText + "%");
            QueryCondition descCond = QueryCondition.create(new QueryColumn("description"),
                    SqlOperator.LIKE, "%" + searchText + "%");
            QueryCondition tagsCond = QueryCondition.create(new QueryColumn("tags"),
                    SqlOperator.LIKE, "%" + searchText + "%");
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
