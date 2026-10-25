package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * 幂等顺序分块写入。分片保存在工程 .agent 临时目录，最后一片完成后才替换正式文件。
 */
@Component
public class ChunkedFileWriter {

    private final CodeFileWriteService files;
    private final ToolRiskRegistry risks;
    private final Object[] lockStripes = new Object[64];

    public ChunkedFileWriter(CodeFileWriteService files, ToolRiskRegistry risks) {
        this.files = files;
        this.risks = risks;
        for (int index = 0; index < lockStripes.length; index++) {
            lockStripes[index] = new Object();
        }
    }

    public Result append(CodeGenTypeEnum type, Long appId, String path, int chunkIndex,
                         String content, boolean lastChunk, Map<String, String> collectedFiles,
                         AgentProgressListener listener) {
        ThrowUtils.throwIf(type == null || appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ToolPaths.validate(path);
        ThrowUtils.throwIf(chunkIndex < 0 || chunkIndex > 100_000, ErrorCode.PARAMS_ERROR,
                "chunkIndex 非法");
        risks.checkChunkContent(content);
        String lockKey = appId + "|" + path;
        Object lock = lockStripes[Math.floorMod(lockKey.hashCode(), lockStripes.length)];
        synchronized (lock) {
            try {
                Path root = Path.of(files.getProjectDir(type, appId))
                        .resolve(".agent").resolve("chunk-writes").resolve(workspaceKey(path));
                Files.createDirectories(root);
                int expected = nextIndex(root);
                if (chunkIndex < expected) {
                    Path previous = part(root, chunkIndex);
                    byte[] incoming = content.getBytes(StandardCharsets.UTF_8);
                    byte[] existing = Files.readAllBytes(previous);
                    if (java.util.Arrays.equals(incoming, existing)) {
                        return new Result(true, false, expected,
                                "分片 " + chunkIndex + " 已存在且内容一致，已幂等忽略");
                    }
                    return new Result(false, false, expected,
                            "分片索引冲突：收到 " + chunkIndex + "，期望 " + expected);
                }
                if (chunkIndex != expected) {
                    return new Result(false, false, expected,
                            "分片乱序：收到 " + chunkIndex + "，期望 " + expected);
                }
                Files.writeString(part(root, chunkIndex), content, StandardCharsets.UTF_8);
                if (!lastChunk) {
                    return new Result(true, false, chunkIndex + 1,
                            "已接收分片 " + chunkIndex + "，下一片索引 " + (chunkIndex + 1));
                }
                String complete = assemble(root, chunkIndex);
                risks.checkWriteContent("writeFileChunk", complete);
                files.saveProjectFile(type, appId, path, complete);
                collectedFiles.put(path, complete);
                if (listener != null) {
                    listener.onFileWritten(path, complete.length(), complete);
                }
                FileUtil.del(root.toFile());
                return new Result(true, true, chunkIndex + 1,
                        "分块写入完成：" + path + "（" + complete.length() + " 字符）");
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("分块写入失败：" + e.getMessage(), e);
            }
        }
    }

    private static int nextIndex(Path root) throws Exception {
        int index = 0;
        while (Files.isRegularFile(part(root, index))) {
            index++;
        }
        return index;
    }

    private static String assemble(Path root, int lastIndex) throws Exception {
        StringBuilder content = new StringBuilder();
        for (int index = 0; index <= lastIndex; index++) {
            Path part = part(root, index);
            if (!Files.isRegularFile(part)) {
                throw new IllegalStateException("缺少分片 " + index);
            }
            content.append(Files.readString(part, StandardCharsets.UTF_8));
        }
        return content.toString();
    }

    private static Path part(Path root, int index) {
        return root.resolve(String.format("part-%06d.txt", index));
    }

    private static String workspaceKey(String path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算分块路径失败", e);
        }
    }

    public record Result(boolean accepted, boolean completed, int nextChunkIndex, String message) {
    }
}
