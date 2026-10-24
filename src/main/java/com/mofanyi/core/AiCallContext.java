package com.mofanyi.core;

/**
 * AI 调用监控上下文：贯通"这次生成/修改是谁、什么类型"。
 * 显式传参而非 ThreadLocal（生成跑在线程池、工具在框架内部线程执行，ThreadLocal 易错绑）。
 * 模型名/最大输出 token 是启动期配置，由 AiMetricsRecorder 注入，不在此携带。
 *
 * @param appId    应用 id
 * @param userId   用户 id（可为 null，如修复构建时无请求上下文）
 * @param callType 调用类型：html / multi_file / vue / modify / fix
 * @param jobId    生成 Job id（可为 null，如非 Job 的构建修复）
 */
public record AiCallContext(Long appId, Long userId, String callType, Long jobId) {

    public static AiCallContext of(Long appId, Long userId, String callType) {
        return new AiCallContext(appId, userId, callType, null);
    }

    public static AiCallContext ofJob(Long appId, Long userId, String callType, Long jobId) {
        return new AiCallContext(appId, userId, callType, jobId);
    }
}
