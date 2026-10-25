package com.mofanyi.core;

import com.mofanyi.ai.SiteModifyAgent;
import com.mofanyi.ai.SiteRepairAgent;
import com.mofanyi.ai.VueProjectAgent;
import com.mofanyi.ai.AestheticReviewService;
import com.mofanyi.ai.reasoning.ReasoningProfileRegistry;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.AppService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VueGenerationVerificationTest {

    @Test
    @DisplayName("Vue 生成后先沙箱构建 dist，再验证构建产物")
    void buildsBeforeVueVerification() {
        VueBuildService buildService = mock(VueBuildService.class);
        BrowserVerifierPort verifier = mock(BrowserVerifierPort.class);
        AiCodeGeneratorFacade facade = new AiCodeGeneratorFacade(
                mock(CodeFileWriteService.class),
                mock(AppService.class),
                mock(VueProjectAgent.class),
                mock(SiteRepairAgent.class),
                mock(SiteModifyAgent.class),
                buildService,
                mock(ReasoningProfileRegistry.class),
                mock(GenerationTaskRegistry.class),
                verifier,
                mock(AestheticReviewService.class),
                mock(ExecutorService.class),
                mock(ThreadPoolExecutor.class));
        ReflectionTestUtils.setField(facade, "renderVerifyEnabled", true);
        when(verifier.verify(CodeGenTypeEnum.VUE, 7L, "before", 99L))
                .thenReturn(new RenderVerifier.RenderReport(List.of(
                        new RenderVerifier.ViewportResult(
                                "desktop", "桌面", 1440, List.of(), false,
                                1440, 1440, 100, List.of(), null, null)), List.of()));

        ReflectionTestUtils.invokeMethod(facade, "afterGenerate",
                CodeGenTypeEnum.VUE, 7L, 99L);

        verify(buildService).build(7L, null);
        verify(verifier).verify(CodeGenTypeEnum.VUE, 7L, "before", 99L);
    }
}
