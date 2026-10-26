package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.core.ToolRiskRegistry;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppFileService {

    public record FileNode(String path, long size, String sha256) {
    }

    public record FileContent(String path, String content, String sha256) {
    }

    private final AppAuthService appAuthService;
    private final CodeFileWriteService files;
    private final AppRevisionService revisions;
    private final ToolRiskRegistry risks;
    private final Map<Long, Object> appLocks = new ConcurrentHashMap<>();

    public AppFileService(AppAuthService appAuthService,
                          CodeFileWriteService files,
                          AppRevisionService revisions,
                          ToolRiskRegistry risks) {
        this.appAuthService = appAuthService;
        this.files = files;
        this.revisions = revisions;
        this.risks = risks;
    }

    public List<FileNode> tree(Long appId, User user) {
        App app = appAuthService.requireCanView(appId, user);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "未知生成类型");
        Path root = Path.of(files.getLiveProjectDir(type, appId));
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<FileNode> result = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !excluded(root.relativize(path)))
                    .limit(5_000)
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path);
                            result.add(new FileNode(
                                    root.relativize(path).toString().replace('\\', '/'),
                                    Files.size(path), sha256(content)));
                        } catch (Exception ignored) {
                            // Binary or concurrently removed file is omitted from the source tree.
                        }
                    });
        } catch (Exception e) {
            throw new IllegalStateException("读取文件树失败", e);
        }
        result.sort(Comparator.comparing(FileNode::path));
        return result;
    }

    public FileContent content(Long appId, String path, User user) {
        App app = appAuthService.requireCanView(appId, user);
        AppContext context = context(app, path);
        String content = files.readFile(
                files.getLiveProjectDir(context.type(), appId), context.path());
        ThrowUtils.throwIf(content == null, ErrorCode.NOT_FOUND_ERROR, "文件不存在");
        return new FileContent(context.path(), content, sha256(content));
    }

    public FileContent save(Long appId, String path, String content,
                            String expectedSha256, User user) {
        App app = appAuthService.requireCanEdit(appId, user);
        AppContext context = context(app, path);
        risks.checkWriteContent("manualEditor", content);
        synchronized (appLocks.computeIfAbsent(appId, ignored -> new Object())) {
            String root = files.getLiveProjectDir(context.type(), appId);
            String oldContent = files.readFile(root, context.path());
            ThrowUtils.throwIf(oldContent == null, ErrorCode.NOT_FOUND_ERROR,
                    "文件不存在，请先通过生成创建文件");
            ThrowUtils.throwIf(StrUtil.isBlank(expectedSha256)
                            || !sha256(oldContent).equalsIgnoreCase(expectedSha256),
                    ErrorCode.OPERATION_ERROR,
                    "VERSION_CONFLICT：文件已变化，请刷新后重试");
            files.saveLiveProjectFile(context.type(), appId, context.path(), content);
            try {
                revisions.createManualRevision(app, user.getId(),
                        "手工编辑 " + context.path());
            } catch (RuntimeException e) {
                files.saveLiveProjectFile(context.type(), appId, context.path(), oldContent);
                throw e;
            }
            return new FileContent(context.path(), content, sha256(content));
        }
    }

    private AppContext context(App app, String path) {
        ToolPaths.validate(path);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == null, ErrorCode.OPERATION_ERROR, "未知生成类型");
        return new AppContext(type, path);
    }

    private boolean excluded(Path relative) {
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("node_modules") || value.equals("dist")
                    || value.equals(".next") || value.equals("out")
                    || value.equals(".agent")) {
                return true;
            }
        }
        return false;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算文件摘要失败", e);
        }
    }

    private record AppContext(CodeGenTypeEnum type, String path) {
    }
}
