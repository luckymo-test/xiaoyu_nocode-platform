package com.mofanyi.ai.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成任务注册表：同时承担两个职责
 * <ul>
 *   <li><b>互斥</b>：同一 appId 同一时刻只允许一个生成任务（{@link #tryAcquire} 抢占）</li>
 *   <li><b>取消</b>：为"停止生成"提供可中断句柄（{@link #cancel}）</li>
 * </ul>
 * 取代了此前 AiCodeGeneratorFacade 里那个"只有 remove、从不 put"的 GENERATING_TASKS 死代码。
 */
@Slf4j
@Component
public class GenerationTaskRegistry {

    private final Map<Long, GenerationTask> tasks = new ConcurrentHashMap<>();

    /**
     * 抢占式登记。已有任务在跑时返回 null（调用方据此拒绝并提示"正在生成中"）。
     */
    public GenerationTask tryAcquire(Long appId, String type) {
        if (appId == null) {
            return null;
        }
        GenerationTask task = new GenerationTask(appId, type);
        return tasks.putIfAbsent(appId, task) == null ? task : null;
    }

    /**
     * 释放任务（必须与 tryAcquire 配对，放在 finally 里）
     */
    public void release(Long appId, GenerationTask task) {
        if (appId == null || task == null) {
            return;
        }
        tasks.remove(appId, task);
    }

    /**
     * 取消某应用正在进行的任务
     *
     * @return 是否确实取消了任务（无任务在跑时返回 false）
     */
    public boolean cancel(Long appId) {
        GenerationTask task = appId == null ? null : tasks.get(appId);
        if (task == null) {
            return false;
        }
        boolean cancelled = task.cancel();
        if (cancelled) {
            log.info("[task] 已请求停止生成：appId={} type={} worker={}", appId, task.type(), task.workerName());
        }
        return cancelled;
    }

    public boolean isRunning(Long appId) {
        return appId != null && tasks.containsKey(appId);
    }

    /**
     * 工具副作用边界上的取消检查：无任务登记时直接放行（如非生成路径）。
     */
    public void checkCancelled(Long appId) {
        if (appId == null) {
            return;
        }
        GenerationTask task = tasks.get(appId);
        if (task != null) {
            task.checkCancelled();
        }
    }

    /**
     * 是否已被请求取消（非抛异常版本，供工具回调使用）。
     * 工具回调里<b>不能靠抛异常终止生成</b>——框架会把它吞成"工具错误结果"喂回模型。
     */
    public boolean isCancelled(Long appId) {
        GenerationTask task = appId == null ? null : tasks.get(appId);
        return task != null && task.isCancelled();
    }

    /**
     * 当前所有在跑任务（供超时回收扫描）
     */
    public Collection<GenerationTask> runningTasks() {
        return List.copyOf(tasks.values());
    }
}
