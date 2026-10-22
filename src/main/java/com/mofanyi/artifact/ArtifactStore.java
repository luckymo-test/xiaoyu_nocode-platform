package com.mofanyi.artifact;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 本地 Artifact Store：保存文件快照、内容摘要与元数据；后续可替换为 COS/S3 实现。
 */
@Service
public class ArtifactStore {

    public record StoredArtifact(String uri, String sha256, long sizeBytes, String mimeType) {
    }

    public StoredArtifact storeFile(Path artifactRoot, String name, Path source,
                                    String mimeType) {
        ThrowUtils.throwIf(artifactRoot == null || source == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(name == null || name.isBlank(), ErrorCode.PARAMS_ERROR);
        if (!Files.isRegularFile(source)) {
            return null;
        }
        Path target = artifactRoot.resolve(name).toAbsolutePath().normalize();
        FileUtil.mkParentDirs(target.toFile());
        try {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new StoredArtifact(target.toUri().toString(), sha256(target),
                    Files.size(target), mimeType);
        } catch (IOException e) {
            throw new IllegalStateException("保存产物失败: " + source, e);
        }
    }

    public StoredArtifact storeText(Path artifactRoot, String name, String content,
                                    String mimeType) {
        ThrowUtils.throwIf(artifactRoot == null || content == null, ErrorCode.PARAMS_ERROR);
        Path target = artifactRoot.resolve(name).toAbsolutePath().normalize();
        FileUtil.mkParentDirs(target.toFile());
        FileUtil.writeString(content, target.toFile(), StandardCharsets.UTF_8);
        try {
            return new StoredArtifact(target.toUri().toString(), sha256(target),
                    Files.size(target), mimeType);
        } catch (IOException e) {
            throw new IllegalStateException("保存文本产物失败: " + name, e);
        }
    }

    public StoredArtifact treeArtifact(Path tree) {
        ThrowUtils.throwIf(tree == null, ErrorCode.PARAMS_ERROR);
        return new StoredArtifact(tree.toUri().toString(), sha256Tree(tree),
                directorySize(tree), "application/vnd.xiaoyu.source-tree");
    }

    public String sha256(Path file) {
        ThrowUtils.throwIf(file == null, ErrorCode.PARAMS_ERROR);
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算文件摘要失败: " + file, e);
        }
    }

    public String sha256Tree(Path root) {
        ThrowUtils.throwIf(root == null, ErrorCode.PARAMS_ERROR);
        if (!Files.isDirectory(root)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算目录摘要失败: " + root, e);
        }
    }

    private long directorySize(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
