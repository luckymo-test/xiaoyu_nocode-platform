package com.mofanyi.core;

import com.mofanyi.entity.AiCallLog;
import com.mofanyi.ai.model.ModelExecutionTrace;
import com.mofanyi.mapper.AiCallLogMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiCallListenerTest {

    @Test
    @DisplayName("调用明细和指标记录实际 provider/model/task")
    void recordsActualModelAudit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetricsRecorder metrics = new AiMetricsRecorder(
                registry, "configured-model", 4096);
        AiCallLogMapper mapper = mock(AiCallLogMapper.class);
        ExecutorService writer = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(writer).submit(any(Runnable.class));
        AiCallListener listener = new AiCallListener(
                metrics, mapper, new ModelExecutionTrace(), writer);

        listener.onSuccess(
                AiCallContext.ofJob(7L, 3L, "generate", 99L),
                "stop", 12, 8, 120, "high",
                "deepseek", "deepseek-chat", "GENERATE");

        ArgumentCaptor<AiCallLog> rowCaptor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(mapper).insert(rowCaptor.capture());
        AiCallLog row = rowCaptor.getValue();
        assertEquals("deepseek", row.getProvider());
        assertEquals(99L, row.getJobId());
        assertEquals("deepseek-chat", row.getModel());
        assertEquals("GENERATE", row.getModelTask());
        Counter total = registry.get("ai_call_total")
                .tags("provider", "deepseek",
                        "model", "deepseek-chat",
                        "call_type", "generate",
                        "status", "success",
                        "model_task", "GENERATE",
                        "reasoning_level", "high")
                .counter();
        assertEquals(1.0, total.count());
    }
}
