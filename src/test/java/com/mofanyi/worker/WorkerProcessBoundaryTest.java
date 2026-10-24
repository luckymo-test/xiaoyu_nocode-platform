package com.mofanyi.worker;

import com.mofanyi.ai.AgentToolLoop;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.BrowserVerifierPort;
import com.mofanyi.core.PlaywrightBrowserVerifier;
import com.mofanyi.core.RedisBrowserVerificationTransport;
import com.mofanyi.job.GenerationJobDispatcher;
import com.mofanyi.job.GenerationJobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "codegen.jobs.execution-mode=worker",
        "spring.task.scheduling.enabled=false",
        "codegen.verify.enabled=false",
        "codegen.browser.engine=playwright",
        "codegen.browser.playwright.transport=redis",
        "server.port=-1",
        "management.server.port=0"
})
class WorkerProcessBoundaryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GenerationWorkerHealthIndicator healthIndicator;

    @Autowired
    private GenerationWorkerConfigurationValidator configurationValidator;

    @Test
    @DisplayName("Worker 进程装配 Job 消费链并暴露执行模式健康信息")
    void workerModeAssemblesConsumerGraph() {
        assertEquals(1, context.getBeanNamesForType(GenerationJobRunner.class).length);
        assertEquals(1, context.getBeanNamesForType(GenerationJobDispatcher.class).length);
        assertEquals(1, context.getBeanNamesForType(AiCodeGeneratorFacade.class).length);
        assertEquals(1, context.getBeanNamesForType(AgentToolLoop.class).length);
        assertEquals(1, context.getBeanNamesForType(
                GenerationWorkerConfigurationValidator.class).length);
        assertEquals(1, context.getBeanNamesForType(BrowserVerifierPort.class).length);
        assertEquals(PlaywrightBrowserVerifier.class,
                context.getBean(BrowserVerifierPort.class).getClass());
        assertEquals(1, context.getBeanNamesForType(
                RedisBrowserVerificationTransport.class).length);

        Health health = healthIndicator.health();
        assertEquals("UP", health.getStatus().getCode());
        assertEquals("worker", health.getDetails().get("mode"));
    }
}
