package com.mofanyi.worker;

import com.mofanyi.xiaoyuZeroCodeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 独立控制面启动入口；与 Worker 共用制品，但不装配生成执行链。
 */
public final class ControlApiApplication {

    private ControlApiApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(xiaoyuZeroCodeApplication.class)
                .profiles("api")
                .run(args);
    }
}
