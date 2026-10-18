package com.mofanyi.ai.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GenerationTaskRegistry} 单元测试：appId 级互斥与取消语义。
 */
class GenerationTaskRegistryTest {

    private static final long APP_ID = 7L;

    private final GenerationTaskRegistry registry = new GenerationTaskRegistry();

    @Test
    @DisplayName("同一 appId 只能抢占一次（互斥），释放后可再次抢占")
    void acquire_isMutuallyExclusive() {
        GenerationTask first = registry.tryAcquire(APP_ID, "html");
        assertNotNull(first);
        assertTrue(registry.isRunning(APP_ID));

        assertNull(registry.tryAcquire(APP_ID, "html"), "已在生成中时不应允许再次抢占");

        registry.release(APP_ID, first);
        assertFalse(registry.isRunning(APP_ID));
        assertNotNull(registry.tryAcquire(APP_ID, "html"), "释放后应可再次抢占");
    }

    @Test
    @DisplayName("不同 appId 互不影响")
    void acquire_differentAppsAreIndependent() {
        assertNotNull(registry.tryAcquire(1L, "html"));
        assertNotNull(registry.tryAcquire(2L, "html"));
    }

    @Test
    @DisplayName("release 只释放自己的任务（防止旧任务误删新任务）")
    void release_onlyRemovesOwnTask() {
        GenerationTask oldTask = registry.tryAcquire(APP_ID, "html");
        registry.release(APP_ID, oldTask);
        GenerationTask newTask = registry.tryAcquire(APP_ID, "html");

        // 旧任务迟到的 release 不应把新任务移除
        registry.release(APP_ID, oldTask);

        assertTrue(registry.isRunning(APP_ID), "新任务不应被旧任务的 release 误删");
        assertFalse(newTask.isCancelled());
    }

    @Test
    @DisplayName("取消后 checkCancelled 抛异常；重复取消只生效一次")
    void cancel_makesTaskInterruptible() {
        GenerationTask task = registry.tryAcquire(APP_ID, "html");

        assertTrue(registry.cancel(APP_ID));
        assertTrue(task.isCancelled());
        assertThrows(GenerationCancelledException.class, task::checkCancelled);
        assertFalse(registry.cancel(APP_ID), "重复取消应返回 false");
    }

    @Test
    @DisplayName("无在跑任务时：cancel 返回 false，checkCancelled 放行")
    void noTask_isSafe() {
        assertFalse(registry.cancel(APP_ID));
        registry.checkCancelled(APP_ID);
        registry.checkCancelled(null);
    }

    @Test
    @DisplayName("runningTasks 返回当前在跑任务")
    void runningTasks_snapshot() {
        registry.tryAcquire(APP_ID, "html");
        assertTrue(registry.runningTasks().stream().anyMatch(t -> APP_ID == t.appId()));
    }

    @Test
    @DisplayName("应用关闭时批量取消全部任务")
    void cancelAll_cancelsEveryRunningTask() {
        GenerationTask first = registry.tryAcquire(1L, "html");
        GenerationTask second = registry.tryAcquire(2L, "vue");

        assertEquals(2, registry.cancelAll());
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertEquals(0, registry.cancelAll());
    }
}
