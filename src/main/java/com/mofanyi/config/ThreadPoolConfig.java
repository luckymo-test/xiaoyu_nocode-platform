package com.mofanyi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private ThreadPoolExecutor buildExecutor(int core, int max, int keepAliveSeconds, int queueCapacity,
                                             String threadNamePrefix, RejectedExecutionHandler handler) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, threadNamePrefix + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(
                core,
                max,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                threadFactory,
                handler);
    }
}
