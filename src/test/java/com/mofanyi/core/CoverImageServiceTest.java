package com.mofanyi.core;

import com.mofanyi.config.CosClientConfig;
import com.mofanyi.config.ScreenshotProperties;
import com.mofanyi.manager.CosManager;
import com.mofanyi.mapper.AppMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CoverImageServiceTest {

    @Test
    @DisplayName("API 模式关闭封面截图后不提交浏览器任务")
    void apiModeDoesNotSubmitBrowserTask() {
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        CoverImageService service = new CoverImageService(
                mock(ScreenshotProperties.class),
                mock(CosManager.class),
                mock(CosClientConfig.class),
                mock(AppMapper.class),
                executor);
        ReflectionTestUtils.setField(service, "captureEnabled", false);

        service.generateCoverAsync(7L, "http://example.test/app");

        verifyNoInteractions(executor);
    }
}
