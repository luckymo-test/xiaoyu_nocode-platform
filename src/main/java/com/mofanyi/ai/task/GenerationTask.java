package com.mofanyi.ai.task;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次生成任务的句柄：持有取消标志与工作线程，供"停止生成"与超时回收使用。
 *
 * <p>取消<b>只置标志，不调用 Thread#interrupt</b>（见 {@link GenerationTask#cancel()} 的说明），
 * 标志在写文件等副作用边界被检查（{@link GenerationTask#checkCancelled()}）。
 */
public final class GenerationTask {

    private final Long appId;
    private final String type;
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread worker;

    public GenerationTask(Long appId, String type) {
        this.appId = appId;
        this.type = type;
    }

    public Long appId() {
        return appId;
    }

    public String type() {
        return type;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public void bind(Thread thread) {
        this.worker = thread;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 在副作用边界检查取消状态；已取消则抛异常中断生成。
     */
    public void checkCancelled() {
        if (cancelled.get()) {
            throw new GenerationCancelledException(appId);
        }
    }

    /**
     * 请求取消：只置标志，由副作用边界（writeFile）检查后抛出中断异常。
     *
     * <p><b>刻意不调用 Thread#interrupt()</b>：实测中断工作线程会连带打断该线程上正在进行的
     * Redis 命令与 COS 上传（RedisCommandInterruptedException / ClosedByInterruptException），
     * 且中断标志会残留到错误处理路径，导致连错误响应都渲染失败（裸 500）。
     * 生成线程是 Tomcat 工作线程或共享池线程，不应被外部中断。
     *
     * <p>重复调用只有第一次生效。
     *
     * @return 本次调用是否真正触发了取消
     */
    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    /**
     * 工作线程名（仅用于日志诊断）
     */
    public String workerName() {
        Thread thread = this.worker;
        return thread == null ? "-" : thread.getName();
    }
}
