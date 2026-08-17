package com.mofanyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Vue 工程生成线程池配置。
 * 对应 application.yml 的 thread-pool.vue 前缀，可快速调整默认线程池参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "thread-pool.vue")
public class ThreadPoolProperties {

    /**
     * 核心线程数
     */
    private int corePoolSize = 2;

    /**
     * 最大线程数
     */
    private int maxPoolSize = 4;

    /**
     * 非核心线程空闲存活时间（秒）
     */
    private int keepAliveSeconds = 60;

    /**
     * 任务队列容量
     */
    private int queueCapacity = 200;

    /**
     * 线程名前缀
     */
    private String threadNamePrefix = "vue-gen-";
}
