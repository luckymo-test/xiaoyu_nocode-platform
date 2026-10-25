package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("分片顺序落盘、重复分片幂等且最后一片提交完整文件")
    void assemblesSequentialChunks() throws Exception {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        ChunkedFileWriter writer =
                new ChunkedFileWriter(files, new ToolRiskRegistry(List.of()));
        Map<String, String> collected = new LinkedHashMap<>();

        ChunkedFileWriter.Result first = writer.append(
                CodeGenTypeEnum.HTML, 7L, "index.html", 0,
                "<main>", false, collected, null);
        ChunkedFileWriter.Result duplicate = writer.append(
                CodeGenTypeEnum.HTML, 7L, "index.html", 0,
                "<main>", false, collected, null);
        ChunkedFileWriter.Result last = writer.append(
                CodeGenTypeEnum.HTML, 7L, "index.html", 1,
                "<h1>hello</h1></main>", true, collected, null);

        assertTrue(first.accepted());
        assertTrue(duplicate.accepted());
        assertFalse(duplicate.completed());
        assertEquals(1, duplicate.nextChunkIndex());
        assertTrue(last.completed());
        assertEquals("<main><h1>hello</h1></main>", collected.get("index.html"));
        String target = files.readFile(files.getProjectDir(CodeGenTypeEnum.HTML, 7L), "index.html");
        assertEquals("<main><h1>hello</h1></main>", target);
        Path chunkRoot = Path.of(files.getProjectDir(CodeGenTypeEnum.HTML, 7L),
                ".agent", "chunk-writes");
        if (Files.isDirectory(chunkRoot)) {
            try (var entries = Files.list(chunkRoot)) {
                assertFalse(entries.findAny().isPresent(), "完成后应清理分片目录");
            }
        }
    }

    @Test
    @DisplayName("分片乱序和路径穿越被拒绝")
    void rejectsOutOfOrderAndTraversal() {
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        ChunkedFileWriter writer =
                new ChunkedFileWriter(files, new ToolRiskRegistry(List.of()));

        ChunkedFileWriter.Result result = writer.append(
                CodeGenTypeEnum.HTML, 8L, "index.html", 2,
                "body", false, new LinkedHashMap<>(), null);
        assertFalse(result.accepted());
        assertThrows(RuntimeException.class, () -> writer.append(
                CodeGenTypeEnum.HTML, 8L, "../escape.js", 0,
                "bad", false, new LinkedHashMap<>(), null));
    }
}
