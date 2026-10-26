package com.mofanyi.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.artifact.ArtifactStore;
import com.mofanyi.ai.CodeGenPromptRegistry;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.ai.model.ModelRegistry;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppArtifact;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppArtifactMapper;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AppRevisionVO;
import com.mofanyi.model.vo.AppRevisionDiffVO;
import com.mofanyi.workspace.WorkspaceManager;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Revision 生命周期：Job 草稿快照、Artifact Manifest、原子发布与回滚。
 */
@Slf4j
@Service
public class AppRevisionService {

    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final AppRevisionMapper revisionMapper;
    private final AppArtifactMapper artifactMapper;
    private final AppMapper appMapper;
    private final WorkspaceManager workspaceManager;
    private final ArtifactStore artifactStore;
    private final CodeFileWriteService codeFileWriteService;
    private final ObjectMapper objectMapper;
    private final GenerationJobService jobService;
    private final ModelRegistry modelRegistry;
    private final ModelExecutionTrace modelExecutionTrace;
    private final CodeGenPromptRegistry promptRegistry;

    public AppRevisionService(AppRevisionMapper revisionMapper,
                              AppArtifactMapper artifactMapper,
                              AppMapper appMapper,
                              WorkspaceManager workspaceManager,
                              ArtifactStore artifactStore,
                              CodeFileWriteService codeFileWriteService,
                              ObjectMapper objectMapper,
                              GenerationJobService jobService,
                              ModelRegistry modelRegistry,
                              ModelExecutionTrace modelExecutionTrace,
                              CodeGenPromptRegistry promptRegistry) {
        this.revisionMapper = revisionMapper;
        this.artifactMapper = artifactMapper;
        this.appMapper = appMapper;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.codeFileWriteService = codeFileWriteService;
        this.objectMapper = objectMapper;
        this.jobService = jobService;
        this.modelRegistry = modelRegistry;
        this.modelExecutionTrace = modelExecutionTrace;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 将 Job 草稿发布为不可变 Revision，并原子切换 live 目录和 currentRevision。
     */
    public AppRevision publishJob(GenerationJob job, WorkspaceManager.WorkspaceHandle handle,
                                  String validationStatus) {
        ThrowUtils.throwIf(job == null || handle == null, ErrorCode.PARAMS_ERROR);
        App app = appMapper.selectOneById(job.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        AppRevision reusable = findRevisionForJob(job);
        if (reusable != null && (STATUS_PUBLISHED.equals(reusable.getStatus())
                || reusable.getId().equals(app.getCurrentRevision()))) {
            publishMarkers(reusable);
            jobService.recordPublishedRevision(job, reusable.getId());
            modelExecutionTrace.clear(job.getId());
            return reusable;
        }
        removeIncompleteRevision(job, reusable);

        long revisionNo = revisionMapper.nextRevisionNo(job.getAppId()) + 1;
        LocalDateTime now = LocalDateTime.now();
        AppRevision revision = AppRevision.builder()
                .workspaceId(job.getWorkspaceId())
                .appId(job.getAppId())
                .jobId(job.getId())
                .revisionNo(revisionNo)
                .baseRevisionId(job.getBaseRevisionId())
                .status(STATUS_CREATED)
                .codeGenType(job.getCodeGenType())
                .stackProfileVersion(job.getStackProfileVersion())
                .templateBundleId(job.getTemplateBundleId())
                .templateVersion(job.getTemplateVersion())
                .templateHash(job.getTemplateHash())
                .skillsJson(job.getSkillsJson())
                .skillsSnapshotJson(job.getSkillsSnapshotJson())
                .sourceUri("pending://" + job.getId())
                .validationStatus(validationStatus)
                .createdBy(job.getRequestedBy())
                .createTime(now)
                .updateTime(now)
                .build();
        ThrowUtils.throwIf(revisionMapper.insertSelective(revision) == 0 || revision.getId() == null,
                ErrorCode.OPERATION_ERROR, "创建 Revision 失败");

        Path source = workspaceManager.snapshot(job, handle, revision.getId());
        revision.setSourceUri(source.toUri().toString());
        List<AppArtifact> artifacts = buildArtifacts(job, handle, revision, source);
        revision.setManifest(writeManifest(revision, artifacts));
        ThrowUtils.throwIf(revisionMapper.update(revision) == 0,
                ErrorCode.OPERATION_ERROR, "更新 Revision 失败");

        swapLiveFiles(app, revision, source, job.getBaseRevisionId());
        publishMarkers(revision);
        jobService.recordPublishedRevision(job, revision.getId());
        modelExecutionTrace.clear(job.getId());
        log.info("[revision] 发布完成，appId={}, revision={}, jobId={}",
                app.getId(), revision.getId(), job.getId());
        return revision;
    }

    /**
     * 发布/回滚到已有不可变 Revision，不重新生成。
     */
    public AppRevisionVO publishExisting(Long appId, Long revisionId, Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(revisionId == null || revisionId <= 0, ErrorCode.PARAMS_ERROR);
        App app = appMapper.selectOneById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        AppRevision revision = revisionMapper.selectOneById(revisionId);
        ThrowUtils.throwIf(revision == null || !appId.equals(revision.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        Path source = Path.of(URI.create(revision.getSourceUri()));
        ThrowUtils.throwIf(!Files.isDirectory(source), ErrorCode.OPERATION_ERROR, "版本源码不存在");
        swapLiveFiles(app, revision, source, app.getCurrentRevision());
        publishMarkers(revision);
        return toVO(revision, true);
    }

    /**
     * Creates an immutable revision from the current live tree after a manual
     * editor save. The caller restores the edited file if this CAS fails.
     */
    public AppRevision createManualRevision(App app, Long userId, String summary) {
        ThrowUtils.throwIf(app == null || app.getId() == null || userId == null,
                ErrorCode.PARAMS_ERROR);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "未知生成类型");
        long revisionNo = revisionMapper.nextRevisionNo(app.getId()) + 1;
        LocalDateTime now = LocalDateTime.now();
        AppRevision revision = AppRevision.builder()
                .workspaceId(app.getWorkspaceId())
                .appId(app.getId())
                .revisionNo(revisionNo)
                .baseRevisionId(app.getCurrentRevision())
                .status(STATUS_CREATED)
                .codeGenType(app.getCodeGenType())
                .sourceUri("pending://manual-" + app.getId() + "-" + revisionNo)
                .validationStatus("MANUAL_EDIT")
                .createdBy(userId)
                .createTime(now)
                .updateTime(now)
                .build();
        revisionMapper.insertSelective(revision);

        Path live = Path.of(codeFileWriteService.getLiveProjectDir(type, app.getId()));
        Path source = Path.of(codeFileWriteService.getTmpRoot(), "apps",
                        String.valueOf(app.getId()), "revisions",
                        String.valueOf(revision.getId()), "source")
                .toAbsolutePath().normalize();
        FileUtil.del(source.toFile());
        FileUtil.mkdir(source.toFile());
        workspaceManager.copyTree(live, source, true);
        revision.setSourceUri(source.toUri().toString());

        ArtifactStore.StoredArtifact tree = artifactStore.treeArtifact(source);
        artifactMapper.insertSelective(AppArtifact.builder()
                .revisionId(revision.getId())
                .appId(app.getId())
                .workspaceId(app.getWorkspaceId())
                .artifactType("SOURCE_TREE")
                .scope("PROJECT")
                .ownerId(userId)
                .visibility(app.getVisibility())
                .uri(tree.uri())
                .sha256(tree.sha256())
                .sizeBytes(tree.sizeBytes())
                .mimeType(tree.mimeType())
                .metadata("{}")
                .createTime(now)
                .build());
        revision.setManifest("{\"changeSummary\":\"" + escapeJson(summary)
                + "\",\"artifact\":\"sha256:" + tree.sha256() + "\"}");
        revisionMapper.update(revision);

        int updated = appMapper.compareAndSetCurrentRevision(
                app.getId(), app.getCurrentRevision(), revision.getId());
        if (updated != 1) {
            artifactMapper.deleteByQuery(QueryWrapper.create()
                    .eq("revisionId", revision.getId()));
            revisionMapper.deleteById(revision.getId());
            FileUtil.del(source.getParent().toFile());
            throw new IllegalStateException("VERSION_CONFLICT：当前版本已被其他操作更新");
        }
        publishMarkers(revision);
        return revision;
    }

    public List<AppRevisionVO> list(Long appId, boolean includeArtifacts) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        return revisionMapper.selectListByQuery(QueryWrapper.create()
                        .eq("appId", appId)
                        .orderBy("revisionNo", false))
                .stream()
                .map(revision -> toVO(revision, includeArtifacts))
                .toList();
    }

    public AppRevisionVO get(Long appId, Long revisionId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(revisionId == null || revisionId <= 0, ErrorCode.PARAMS_ERROR);
        AppRevision revision = revisionMapper.selectOneById(revisionId);
        ThrowUtils.throwIf(revision == null || !appId.equals(revision.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        return toVO(revision, true);
    }

    public AppRevisionDiffVO compare(Long appId, Long fromRevisionId, Long toRevisionId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        AppRevision from = requireRevision(appId, fromRevisionId);
        AppRevision to = requireRevision(appId, toRevisionId);
        Map<String, String> fromFiles = fileHashes(Path.of(URI.create(from.getSourceUri())));
        Map<String, String> toFiles = fileHashes(Path.of(URI.create(to.getSourceUri())));
        AppRevisionDiffVO diff = new AppRevisionDiffVO();
        diff.setFromRevisionId(fromRevisionId);
        diff.setToRevisionId(toRevisionId);
        TreeMap<String, AppRevisionDiffVO.FileChange> changes = new TreeMap<>();
        for (Map.Entry<String, String> entry : fromFiles.entrySet()) {
            String targetHash = toFiles.get(entry.getKey());
            if (targetHash == null) {
                String oldContent = readCapped(from, entry.getKey());
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "DELETED", entry.getValue(), null,
                        unifiedDiff(entry.getKey(), oldContent, ""),
                        0, lineCount(oldContent)));
            } else if (!entry.getValue().equals(targetHash)) {
                String oldContent = readCapped(from, entry.getKey());
                String newContent = readCapped(to, entry.getKey());
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "MODIFIED", entry.getValue(), targetHash,
                        unifiedDiff(entry.getKey(), oldContent, newContent),
                        lineCount(newContent), lineCount(oldContent)));
            }
        }
        for (Map.Entry<String, String> entry : toFiles.entrySet()) {
            if (!fromFiles.containsKey(entry.getKey())) {
                String newContent = readCapped(to, entry.getKey());
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "ADDED", null, entry.getValue(),
                        unifiedDiff(entry.getKey(), "", newContent),
                        lineCount(newContent), 0));
            }
        }
        diff.setFiles(new ArrayList<>(changes.values()));
        return diff;
    }

    private List<AppArtifact> buildArtifacts(GenerationJob job,
                                             WorkspaceManager.WorkspaceHandle handle,
                                             AppRevision revision,
                                             Path source) {
        Path artifactRoot = source.getParent().resolve("artifacts");
        FileUtil.mkdir(artifactRoot.toFile());
        List<AppArtifact> artifacts = new ArrayList<>();

        ArtifactStore.StoredArtifact sourceTree = artifactStore.treeArtifact(source);
        artifacts.add(newArtifact(job, revision, "SOURCE_TREE", sourceTree, "{}"));

        addFileArtifact(job, revision, artifacts, artifactRoot, "MATERIAL_MANIFEST",
                handle.root().resolve(".agent/materials.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "RENDER_REPORT_BEFORE",
                handle.root().resolve(".agent/verify-before.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "RENDER_REPORT_AFTER",
                handle.root().resolve(".agent/verify-after.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "NETWORK_FAILURE_BEFORE",
                handle.root().resolve(".agent/network-before.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "NETWORK_FAILURE_AFTER",
                handle.root().resolve(".agent/network-after.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "CONSOLE_ERROR_BEFORE",
                handle.root().resolve(".agent/console-before.json"), "application/json", "{}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "CONSOLE_ERROR_AFTER",
                handle.root().resolve(".agent/console-after.json"), "application/json", "{}");
        addNamedFileArtifact(job, revision, artifacts, artifactRoot, "BROWSER_TRACE_BEFORE",
                handle.root().resolve(".agent/trace-before.zip"),
                "browser-trace-before.zip", "application/zip");
        addNamedFileArtifact(job, revision, artifacts, artifactRoot, "BROWSER_TRACE_AFTER",
                handle.root().resolve(".agent/trace-after.zip"),
                "browser-trace-after.zip", "application/zip");
        addScreenshotArtifacts(job, revision, artifacts, artifactRoot,
                handle.root().resolve(".agent/screenshots"));
        addFileArtifact(job, revision, artifacts, artifactRoot, "VISUAL_DIFF",
                handle.root().resolve(".agent/screenshots/visual-diff.json"),
                "application/json", "{\"semantics\":\"pixel-change-only\"}");
        addFileArtifact(job, revision, artifacts, artifactRoot, "BUILD_REPORT",
                handle.root().resolve(".agent/build-report.json"),
                "application/json", "{}");

        ArtifactStore.StoredArtifact request = artifactStore.storeText(
                artifactRoot, "requirement-baseline.json", job.getRequestPayload(),
                "application/json");
        artifacts.add(newArtifact(job, revision, "REQUIREMENT_BASELINE", request, "{}"));

        Path dist = handle.root().resolve("dist");
        if (Files.isDirectory(dist)) {
            Path buildSnapshot = source.getParent().resolve("build");
            workspaceManager.copyTree(dist, buildSnapshot, false);
            ArtifactStore.StoredArtifact build = artifactStore.treeArtifact(buildSnapshot);
            artifacts.add(newArtifact(job, revision, "BUILD_OUTPUT", build, "{}"));
        }

        for (AppArtifact artifact : artifacts) {
            ThrowUtils.throwIf(artifactMapper.insertSelective(artifact) == 0,
                    ErrorCode.OPERATION_ERROR, "保存 Artifact 失败");
        }
        return artifacts;
    }

    private void addFileArtifact(GenerationJob job, AppRevision revision,
                                 List<AppArtifact> artifacts, Path artifactRoot,
                                 String type, Path source, String mimeType, String metadata) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        String name = type.toLowerCase().replace('_', '-') + ".json";
        ArtifactStore.StoredArtifact stored = artifactStore.storeFile(
                artifactRoot, name, source, mimeType);
        artifacts.add(newArtifact(job, revision, type, stored, metadata));
    }

    private void addNamedFileArtifact(GenerationJob job, AppRevision revision,
                                      List<AppArtifact> artifacts, Path artifactRoot,
                                      String type, Path source, String name, String mimeType) {
        if (!Files.isRegularFile(source)) {
            return;
        }
        ArtifactStore.StoredArtifact stored = artifactStore.storeFile(
                artifactRoot, name, source, mimeType);
        artifacts.add(newArtifact(job, revision, type, stored,
                "{\"source\":\"" + source.getFileName() + "\"}"));
    }

    private void addScreenshotArtifacts(GenerationJob job, AppRevision revision,
                                        List<AppArtifact> artifacts, Path artifactRoot,
                                        Path screenshotDir) {
        if (!Files.isDirectory(screenshotDir)) {
            return;
        }
        try (var files = Files.list(screenshotDir)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        String name = "screenshot-" + path.getFileName();
                        ArtifactStore.StoredArtifact stored = artifactStore.storeFile(
                                artifactRoot, name, path, "image/png");
                        if (stored != null) {
                            artifacts.add(newArtifact(job, revision,
                                    "VIEWPORT_SCREENSHOT", stored,
                                    "{\"file\":\"" + path.getFileName() + "\"}"));
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("收集视口截图失败", e);
        }
    }

    private AppArtifact newArtifact(GenerationJob job, AppRevision revision, String type,
                                    ArtifactStore.StoredArtifact stored, String metadata) {
        return AppArtifact.builder()
                .revisionId(revision.getId())
                .jobId(job.getId())
                .appId(job.getAppId())
                .workspaceId(job.getWorkspaceId())
                .artifactType(type)
                .scope("PROJECT")
                .ownerId(job.getRequestedBy())
                .visibility(job.getVisibility())
                .uri(stored.uri())
                .sha256(stored.sha256())
                .sizeBytes(stored.sizeBytes())
                .mimeType(stored.mimeType())
                .metadata(metadata)
                .createTime(LocalDateTime.now())
                .build();
    }

    private String writeManifest(AppRevision revision, List<AppArtifact> artifacts) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("revision", revision.getRevisionNo());
        manifest.put("revisionId", String.valueOf(revision.getId()));
        manifest.put("jobId", String.valueOf(revision.getJobId()));
        manifest.put("baseRevision", revision.getBaseRevisionId() == null
                ? null : String.valueOf(revision.getBaseRevisionId()));
        manifest.put("stackProfile", revision.getCodeGenType());
        if (StrUtil.isNotBlank(revision.getStackProfileVersion())) {
            manifest.put("stackProfileVersion", revision.getStackProfileVersion());
        }
        manifest.put("promptVersions", promptRegistry.versions());
        manifest.put("modelExecution",
                modelExecutionTrace.snapshot(revision.getJobId(), modelRegistry));
        if (revision.getTemplateBundleId() != null) {
            manifest.put("template", Map.of(
                    "id", revision.getTemplateBundleId(),
                    "version", revision.getTemplateVersion(),
                    "hash", revision.getTemplateHash()));
        }
        if (StrUtil.isNotBlank(revision.getSkillsSnapshotJson())) {
            try {
                manifest.put("skills", objectMapper.readValue(
                        revision.getSkillsSnapshotJson(), List.class));
            } catch (Exception ignored) {
                manifest.put("skills", List.of());
            }
        } else if (StrUtil.isNotBlank(revision.getSkillsJson())) {
            try {
                manifest.put("skills", objectMapper.readValue(revision.getSkillsJson(), List.class));
            } catch (Exception ignored) {
                manifest.put("skills", List.of());
            }
        }
        String validation = revision.getValidationStatus();
        manifest.put("status", "ok".equals(validation) || "NOT_APPLICABLE".equals(validation)
                || "fixed".equals(validation) ? "VALIDATED" : "PUBLISHED_WITH_WARNINGS");
        Map<String, String> artifactHashes = new LinkedHashMap<>();
        for (AppArtifact artifact : artifacts) {
            artifactHashes.put(artifact.getArtifactType(), "sha256:" + artifact.getSha256());
        }
        manifest.put("artifacts", artifactHashes);
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Revision Manifest 失败", e);
        }
    }

    private void swapLiveFiles(App app, AppRevision revision, Path source,
                               Long expectedRevisionId) {
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(revision.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "版本生成类型未知");
        Path live = Path.of(codeFileWriteService.getLiveProjectDir(type, app.getId()));
        Path parent = live.getParent();
        Path stage = parent.resolve(live.getFileName() + ".publish-" + revision.getId());
        Path backup = parent.resolve(live.getFileName() + ".backup-" + revision.getId());
        FileUtil.del(stage.toFile());
        FileUtil.del(backup.toFile());
        FileUtil.mkdir(parent.toFile());
        workspaceManager.copyTree(source, stage, false);

        boolean movedLive = false;
        boolean movedStage = false;
        try {
            if (Files.isDirectory(live)) {
                move(live, backup);
                movedLive = true;
            }
            move(stage, live);
            movedStage = true;
        } catch (RuntimeException e) {
            rollbackFiles(live, backup, movedLive, movedStage);
            throw e;
        }

        int updated = appMapper.compareAndSetCurrentRevision(
                app.getId(), expectedRevisionId, revision.getId());
        if (updated != 1) {
            rollbackFiles(live, backup, movedLive, true);
            throw new IllegalStateException("当前版本已被其他操作更新，发布已回滚");
        }
        FileUtil.del(backup.toFile());
    }

    private void rollbackFiles(Path live, Path backup, boolean movedLive, boolean movedStage) {
        try {
            if (movedStage) {
                FileUtil.del(live.toFile());
            }
            if (movedLive && Files.exists(backup)) {
                move(backup, live);
            }
        } catch (RuntimeException e) {
            log.error("[revision] 发布失败后恢复 live 目录失败，live={}, backup={}",
                    live, backup, e);
        }
    }

    private void publishMarkers(AppRevision revision) {
        AppRevision update = new AppRevision();
        update.setId(revision.getId());
        update.setStatus(STATUS_PUBLISHED);
        update.setPublishedAt(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        revisionMapper.update(update);
        revisionMapper.supersedePublished(revision.getAppId(), revision.getId());
        revision.setStatus(STATUS_PUBLISHED);
    }

    private void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            throw new IllegalStateException("移动目录失败: " + source + " -> " + target, e);
        }
    }

    private AppRevision findRevisionForJob(GenerationJob job) {
        return revisionMapper.selectOneByQuery(QueryWrapper.create()
                .eq("jobId", job.getId())
                .limit(1));
    }

    private void removeIncompleteRevision(GenerationJob job, AppRevision existing) {
        if (existing == null) {
            return;
        }
        artifactMapper.deleteByQuery(QueryWrapper.create().eq("revisionId", existing.getId()));
        revisionMapper.deleteById(existing.getId());
        String sourceUri = existing.getSourceUri();
        if (StrUtil.isNotBlank(sourceUri) && !sourceUri.startsWith("pending://")) {
            Path source = Path.of(URI.create(sourceUri));
            Path revisionRoot = source.getParent();
            if (revisionRoot != null) {
                FileUtil.del(revisionRoot.toFile());
            }
        }
    }

    private AppRevisionVO toVO(AppRevision revision, boolean includeArtifacts) {
        AppRevisionVO vo = new AppRevisionVO();
        vo.setId(revision.getId());
        vo.setAppId(revision.getAppId());
        vo.setWorkspaceId(revision.getWorkspaceId());
        vo.setJobId(revision.getJobId());
        vo.setRevisionNo(revision.getRevisionNo());
        vo.setBaseRevisionId(revision.getBaseRevisionId());
        vo.setStatus(revision.getStatus());
        vo.setCodeGenType(revision.getCodeGenType());
        vo.setStackProfileVersion(revision.getStackProfileVersion());
        vo.setTemplateBundleId(revision.getTemplateBundleId());
        vo.setTemplateVersion(revision.getTemplateVersion());
        vo.setTemplateHash(revision.getTemplateHash());
        vo.setSkillIds(readSkillIds(revision.getSkillsJson()));
        vo.setSourceUri(revision.getSourceUri());
        vo.setValidationStatus(revision.getValidationStatus());
        vo.setCreatedBy(revision.getCreatedBy());
        vo.setPublishedAt(revision.getPublishedAt());
        vo.setCreateTime(revision.getCreateTime());
        if (includeArtifacts) {
            vo.setArtifacts(artifactMapper.selectListByQuery(QueryWrapper.create()
                    .eq("revisionId", revision.getId())
                    .orderBy("artifactType", true)));
        }
        return vo;
    }

    private AppRevision requireRevision(Long appId, Long revisionId) {
        ThrowUtils.throwIf(revisionId == null || revisionId <= 0, ErrorCode.PARAMS_ERROR);
        AppRevision revision = revisionMapper.selectOneById(revisionId);
        ThrowUtils.throwIf(revision == null || !appId.equals(revision.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "版本不存在");
        return revision;
    }

    private Map<String, String> fileHashes(Path root) {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return hashes;
        }
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                hashes.put(relative, artifactStore.sha256(path));
            });
            return hashes;
        } catch (IOException e) {
            throw new IllegalStateException("读取版本文件树失败: " + root, e);
        }
    }

    private String readCapped(AppRevision revision, String path) {
        try {
            Path root = Path.of(URI.create(revision.getSourceUri()));
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return "";
            }
            return StrUtil.maxLength(Files.readString(file), 20_000);
        } catch (Exception e) {
            return "";
        }
    }

    private String unifiedDiff(String path, String before, String after) {
        StringBuilder diff = new StringBuilder("--- a/").append(path)
                .append("\n+++ b/").append(path).append('\n');
        for (String line : before.split("\\R", -1)) {
            diff.append('-').append(StrUtil.maxLength(line, 1_000)).append('\n');
        }
        for (String line : after.split("\\R", -1)) {
            diff.append('+').append(StrUtil.maxLength(line, 1_000)).append('\n');
        }
        return StrUtil.maxLength(diff.toString(), 20_000);
    }

    private int lineCount(String value) {
        return value == null || value.isEmpty() ? 0 : value.split("\\R", -1).length;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
    }

    private List<String> readSkillIds(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
