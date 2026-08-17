package com.mofanyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 封面截图线程池配置。
 * 对应 application.yml 的 thread-pool.screenshot 前缀。
 */
@Data
@Component
@ConfigurationProperties(prefix = "thread-pool.screenshot")
public class ScreenshotPoolProperties {

    /**
     * 核心线程数
     */
    private int corePoolSize = 1;

    /**
     * 最大线程数
     */
    private int maxPoolSize = 2;

    /**
     * 非核心线程空闲存活时间（秒）
     */
    private int keepAliveSeconds = 60;

    /**
     * 任务队列容量
     */
    private int queueCapacity = 50;

    /**
     * 线程名前缀
     */
    private String threadNamePrefix = "screenshot-";
}
