package com.mofanyi.core;

import com.mofanyi.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ToolPaths} 路径安全测试：这是 agent 写文件的安全底线，必须有回归覆盖
 * （plan-refactor 6.3 —— 此前 src/test 为空，正是这类缺陷能溜进来的原因）。
 */
class ToolPathsTest {

    @Test
    @DisplayName("放行正常相对路径")
    void allowsRelativePaths() {
        assertDoesNotThrow(() -> ToolPaths.validate("index.html"));
        assertDoesNotThrow(() -> ToolPaths.validate("css/styles.css"));
        assertDoesNotThrow(() -> ToolPaths.validate("src/components/App.vue"));
    }

    @Test
    @DisplayName("拒绝路径穿越（../）")
    void rejectsTraversal() {
        assertThrows(BusinessException.class, () -> ToolPaths.validate("../secret.txt"));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("a/../../b.txt"));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("..\\..\\windows\\system32\\x"));
    }

    @Test
    @DisplayName("拒绝绝对路径与盘符")
    void rejectsAbsolutePaths() {
        assertThrows(BusinessException.class, () -> ToolPaths.validate("/etc/passwd"));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("\\windows\\x.txt"));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("C:\\Users\\x.txt"));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("d:/tmp/x.txt"));
    }

    @Test
    @DisplayName("拒绝空路径")
    void rejectsBlank() {
        assertThrows(BusinessException.class, () -> ToolPaths.validate(null));
        assertThrows(BusinessException.class, () -> ToolPaths.validate(""));
        assertThrows(BusinessException.class, () -> ToolPaths.validate("   "));
    }
}
