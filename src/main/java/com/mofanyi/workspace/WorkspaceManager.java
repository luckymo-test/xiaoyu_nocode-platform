package com.mofanyi.workspace;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 隔离工作区：每次 Job 一个 run workspace，成功后快照成不可变 Revision。
 */
@Slf4j
@Service
public class WorkspaceManager {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", "dist", "build", "target", ".git", ".idea", ".vscode", "__pycache__");

    private final CodeFileWriteService codeFileWriteService;
    private final ProjectWorkspaceContext workspaceContext;
    private final AppRevisionMapper revisionMapper;

    public WorkspaceManager(CodeFileWriteService codeFileWriteService,
                            ProjectWorkspaceContext workspaceContext,
                            AppRevisionMapper revisionMapper) {
        this.codeFileWriteService = codeFileWriteService;
        this.workspaceContext = workspaceContext;
        this.revisionMapper = revisionMapper;
    }

    public record WorkspaceHandle(Long appId, Path root) {
    }

    public WorkspaceHandle begin(GenerationJob job) {
        ThrowUtils.throwIf(job == null || job.getId() == null || job.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(job.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR, "未知生成类型");
        Path runRoot = Paths.get(codeFileWriteService.getTmpRoot(), "runs",
                String.valueOf(job.getId())).toAbsolutePath().normalize();
        Path workspace = runRoot.resolve("workspace");
        FileUtil.del(runRoot.toFile());
        FileUtil.mkdir(workspace.toFile());
        Path live = Path.of(codeFileWriteService.getLiveProjectDir(type, job.getAppId()));
        if (Files.isDirectory(live)) {
            copyTree(live, workspace, true);
        } else if (job.getBaseRevisionId() != null) {
            AppRevision base = revisionMapper.selectOneById(job.getBaseRevisionId());
            if (base != null && base.getSourceUri() != null) {
                copyTree(Path.of(java.net.URI.create(base.getSourceUri())), workspace, true);
            }
        }
        workspaceContext.activate(job.getAppId(), workspace);
        log.info("[workspace] 创建隔离草稿，jobId={}, appId={}, root={}",
                job.getId(), job.getAppId(), workspace);
        return new WorkspaceHandle(job.getAppId(), workspace);
    }

    public Path snapshot(GenerationJob job, WorkspaceHandle handle, Long revisionId) {
        ThrowUtils.throwIf(job == null || handle == null || revisionId == null, ErrorCode.PARAMS_ERROR);
        Path source = Paths.get(codeFileWriteService.getTmpRoot(), "apps",
                String.valueOf(job.getAppId()), "revisions", String.valueOf(revisionId), "source")
                .toAbsolutePath().normalize();
        FileUtil.del(source.toFile());
        FileUtil.mkdir(source.toFile());
        copyTree(handle.root(), source, true);
        return source;
    }

    public void close(WorkspaceHandle handle) {
        if (handle == null) {
            return;
        }
        workspaceContext.deactivate(handle.appId(), handle.root());
        Path runRoot = handle.root().getParent();
        if (runRoot != null) {
            FileUtil.del(runRoot.toFile());
        }
    }

    public void copyTree(Path source, Path target, boolean excludeHeavyDirs) {
        ThrowUtils.throwIf(source == null || target == null, ErrorCode.PARAMS_ERROR);
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) {
                    return;
                }
                if (excludeHeavyDirs && containsExcludedDir(relative)) {
                    return;
                }
                Path destination = target.resolve(relative);
                try {
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("复制工作区失败: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("遍历工作区失败: " + source, e);
        }
    }

    private boolean containsExcludedDir(Path relative) {
        for (Path part : relative) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
