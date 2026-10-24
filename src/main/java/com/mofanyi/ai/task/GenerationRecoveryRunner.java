package com.mofanyi.ai.task;

import com.mofanyi.model.enums.AppGenerateStatusEnum;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.service.AppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 生成状态机的"终态回收"（plan-refactor P0-3）。
 *
 * <p>解决两个场景：
 * <ol>
 *   <li><b>进程崩溃遗留</b>：启动时内存中没有任何生成任务，故所有 {@code generating} 记录都是上次进程的僵尸，统一置 failed。</li>
 *   <li><b>任务卡死</b>：运行中超过阈值仍未结束的任务，强制取消并置 failed，避免长期占用 appId（互斥）导致用户再也发不起生成。</li>
 * </ol>
 */
@Slf4j
@Component
@Conditional(GenerationExecutionCondition.class)
public class GenerationRecoveryRunner implements ApplicationRunner {

    /**
     * 单次生成允许的最长运行时长（默认 30 分钟），包含素材预取、模型生成与交付检测。
     */
    @Value("${codegen.generation.timeout-millis:1800000}")
    private long timeoutMillis;

    private final GenerationTaskRegistry taskRegistry;
    private final AppService appService;

    public GenerationRecoveryRunner(GenerationTaskRegistry taskRegistry, AppService appService) {
        this.taskRegistry = taskRegistry;
        this.appService = appService;
    }

    /**
     * 启动时回收崩溃遗留的 generating 记录
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = appService.failStaleGenerating();
            if (recovered > 0) {
                log.warn("[task] 启动回收 {} 条卡在 generating 的僵尸记录", recovered);
            }
        } catch (RuntimeException e) {
            log.warn("[task] 启动回收 generating 记录失败", e);
        }
    }

    /**
     * 周期性扫描超时任务（默认每 2 分钟一次）
     */
    @Scheduled(fixedDelayString = "${codegen.generation.sweep-interval-millis:120000}")
    public void sweepTimeout() {
        for (GenerationTask task : taskRegistry.runningTasks()) {
            if (task.elapsedMillis() <= timeoutMillis) {
                continue;
            }
            log.warn("[task] 生成超时强制停止：appId={} type={} 已运行 {} ms",
                    task.appId(), task.type(), task.elapsedMillis());
            task.cancel();
            try {
                appService.updateGenerateStatus(task.appId(), AppGenerateStatusEnum.FAILED.getValue());
            } catch (RuntimeException e) {
                log.warn("[task] 超时任务状态更新失败，appId={}", task.appId(), e);
            }
        }
    }
}
