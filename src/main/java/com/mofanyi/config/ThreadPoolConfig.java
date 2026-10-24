package com.mofanyi.config;

import com.mofanyi.job.GenerationExecutionCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池配置：由 Spring 容器管理 ThreadPoolExecutor Bean。
 * vue 线程池拒绝策略为 CallerRunsPolicy（任务在调用线程执行，不丢弃任务）。
 * 截图线程池拒绝策略为丢弃并记日志（截图失败不影响部署，不能让任务回退到部署线程阻塞执行）。
 */
@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Bean
    public ThreadPoolExecutor vueThreadPoolExecutor(ThreadPoolProperties properties) {
        return buildExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveSeconds(),
                properties.getQueueCapacity(),
                properties.getThreadNamePrefix(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    public ThreadPoolExecutor screenshotThreadPoolExecutor(ScreenshotPoolProperties properties) {
        RejectedExecutionHandler discardWithLog = (r, executor) ->
                log.warn("截图任务被拒绝丢弃，队列已满：{}", r);
        return buildExecutor(properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveSeconds(),
                properties.getQueueCapacity(),
                properties.getThreadNamePrefix(),
                discardWithLog);
    }

    /**
     * AI 调用明细异步落库执行器：短任务、IO 等待型，用虚拟线程（创建成本低、不占平台线程）。
     * 由 Spring 管理，替代此前在 AiCallListener 里自行 new 的做法（违反项目规范 #63）。
     */
    @Bean(destroyMethod = "close")
    public ExecutorService aiCallDetailExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 渲染修复执行器：交付后异步修复渲染问题（plan-agent-pipeline P0-4）。
     * 用虚拟线程——修复是长耗时的模型调用 + 浏览器复验，隔离在独立执行器里，
     * 不占用生成线程池，也不会因生成线程池满而阻塞。
     */
    @Bean(destroyMethod = "close")
    @Conditional(GenerationExecutionCondition.class)
    public ExecutorService renderFixExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Job 执行使用有界平台线程池，保证 Worker 并发数可控且不会无限创建生成任务。
     */
    @Bean(destroyMethod = "shutdownNow")
    @Conditional(GenerationExecutionCondition.class)
    public ThreadPoolExecutor jobExecutionExecutor(
            @Value("${codegen.generation-worker.consumer-concurrency:2}") int concurrency) {
        int threads = Math.max(1, Math.min(32, concurrency));
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 4),
                daemonFactory("generation-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "close")
    public ExecutorService jobStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 单个平台调度线程用于取消轮询与租约心跳；实际生成仍在线程池 Worker 中。
     */
    @Bean(destroyMethod = "shutdownNow")
    @Conditional(GenerationExecutionCondition.class)
    public ScheduledExecutorService jobHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "job-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    private ThreadPoolExecutor buildExecutor(int core, int max, int keepAliveSeconds, int queueCapacity,
                                             String threadNamePrefix, RejectedExecutionHandler handler) {
        return new ThreadPoolExecutor(
                core,
                max,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                daemonFactory(threadNamePrefix),
                handler);
    }

    private static ThreadFactory daemonFactory(String threadNamePrefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, threadNamePrefix + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
