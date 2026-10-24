package com.mofanyi.job;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 只有 embedded/worker 进程装配生成执行链；api 进程只保留控制面。
 */
public class GenerationExecutionCondition implements Condition {

    public static final String MODE_PROPERTY = "codegen.jobs.execution-mode";
    public static final String EMBEDDED = "embedded";
    public static final String API = "api";
    public static final String WORKER = "worker";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty(MODE_PROPERTY, EMBEDDED)
                .trim().toLowerCase();
        return EMBEDDED.equals(mode) || WORKER.equals(mode);
    }
}
