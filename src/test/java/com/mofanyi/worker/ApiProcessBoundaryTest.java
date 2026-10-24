package com.mofanyi.worker;

import com.mofanyi.ai.AgentToolLoop;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.VueProjectAgent;
import com.mofanyi.controller.AiChatController;
import com.mofanyi.controller.GenerationJobController;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.BrowserVerifierPort;
import com.mofanyi.core.VueBuildService;
import com.mofanyi.job.GenerationJobDispatcher;
import com.mofanyi.job.GenerationJobRunner;
import com.mofanyi.service.GenerationJobService;
import com.mofanyi.service.GenerationJobSseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "codegen.jobs.execution-mode=api",
        "spring.main.lazy-initialization=true"
})
class ApiProcessBoundaryTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("API 进程只装配控制面，不创建生成、模型、浏览器或 npm 执行入口")
    void apiModeExcludesGenerationExecutionGraph() {
        for (Class<?> type : new Class<?>[]{
                GenerationJobRunner.class,
                GenerationJobDispatcher.class,
                AiCodeGeneratorFacade.class,
                BrowserVerifierPort.class,
                AgentToolLoop.class,
                VueProjectAgent.class,
                SiteRepairAgent.class,
                VueBuildService.class,
                AiChatController.class
        }) {
            assertEquals(0, context.getBeanNamesForType(type).length, type.getSimpleName());
        }
        assertNotNull(context.getBean(GenerationJobController.class));
        assertNotNull(context.getBean(GenerationJobService.class));
        assertNotNull(context.getBean(GenerationJobSseService.class));
    }
}
