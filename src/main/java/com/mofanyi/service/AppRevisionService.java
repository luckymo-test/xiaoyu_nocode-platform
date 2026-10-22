package com.mofanyi.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.artifact.ArtifactStore;
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

    public AppRevisionService(AppRevisionMapper revisionMapper,
                              AppArtifactMapper artifactMapper,
                              AppMapper appMapper,
                              WorkspaceManager workspaceManager,
                              ArtifactStore artifactStore,
                              CodeFileWriteService codeFileWriteService,
                              ObjectMapper objectMapper,
                              GenerationJobService jobService) {
        this.revisionMapper = revisionMapper;
        this.artifactMapper = artifactMapper;
        this.appMapper = appMapper;
        this.workspaceManager = workspaceManager;
        this.artifactStore = artifactStore;
        this.codeFileWriteService = codeFileWriteService;
        this.objectMapper = objectMapper;
        this.jobService = jobService;
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
                .templateBundleId(job.getTemplateBundleId())
                .templateVersion(job.getTemplateVersion())
                .templateHash(job.getTemplateHash())
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
        ThrowUtils.throwIf(!Long.valueOf(userId).equals(app.getUserId()),
                ErrorCode.NO_AUTH_ERROR, "只能发布自己的应用版本");
        Path source = Path.of(URI.create(revision.getSourceUri()));
        ThrowUtils.throwIf(!Files.isDirectory(source), ErrorCode.OPERATION_ERROR, "版本源码不存在");
        swapLiveFiles(app, revision, source, app.getCurrentRevision());
        publishMarkers(revision);
        return toVO(revision, true);
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
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "DELETED", entry.getValue(), null));
            } else if (!entry.getValue().equals(targetHash)) {
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "MODIFIED", entry.getValue(), targetHash));
            }
        }
        for (Map.Entry<String, String> entry : toFiles.entrySet()) {
            if (!fromFiles.containsKey(entry.getKey())) {
                changes.put(entry.getKey(), new AppRevisionDiffVO.FileChange(
                        entry.getKey(), "ADDED", null, entry.getValue()));
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
        addScreenshotArtifacts(job, revision, artifacts, artifactRoot,
                handle.root().resolve(".agent/screenshots"));
        addFileArtifact(job, revision, artifacts, artifactRoot, "VISUAL_DIFF",
                handle.root().resolve(".agent/screenshots/visual-diff.json"),
                "application/json", "{\"semantics\":\"pixel-change-only\"}");

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
        if (revision.getTemplateBundleId() != null) {
            manifest.put("template", Map.of(
                    "id", revision.getTemplateBundleId(),
                    "version", revision.getTemplateVersion(),
                    "hash", revision.getTemplateHash()));
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
        vo.setTemplateBundleId(revision.getTemplateBundleId());
        vo.setTemplateVersion(revision.getTemplateVersion());
        vo.setTemplateHash(revision.getTemplateHash());
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
}
