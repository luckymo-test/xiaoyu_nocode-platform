package com.mofanyi.core;

import com.mofanyi.ai.SiteModifyAgent;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.VueProjectAgent;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.service.AppService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiCodeGeneratorFacadeShutdownTest {

    @Test
    @DisplayName("应用关闭时先取消任务并中断生成/修复线程池")
    void shutdownCancelsBackgroundWorkBeforeDataSourceCloses() {
        GenerationTaskRegistry taskRegistry = mock(GenerationTaskRegistry.class);
        ThreadPoolExecutor generationPool = mock(ThreadPoolExecutor.class);
        ExecutorService renderFixExecutor = mock(ExecutorService.class);
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade(
                mock(CodeFileWriteService.class),
                mock(AppService.class),
                mock(VueProjectAgent.class),
                mock(SiteRepairAgent.class),
                mock(SiteModifyAgent.class),
                mock(VueBuildService.class),
                mock(ReasoningProfileRegistry.class),
                taskRegistry,
                mock(BrowserVerifierPort.class),
                renderFixExecutor,
                generationPool);

        facade.shutdownGenerationTasks();

        verify(taskRegistry).cancelAll();
        verify(generationPool).shutdownNow();
        verify(renderFixExecutor).shutdownNow();
    }
}
