package com.mofanyi.worker;

import com.mofanyi.xiaoyuZeroCodeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 独立生成 Worker 启动入口。业务 API 端口默认关闭，仅保留独立 management 端口。
 */
public final class GenerationWorkerApplication {

    private GenerationWorkerApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(xiaoyuZeroCodeApplication.class)
                .profiles("worker")
                .run(args);
    }
}
