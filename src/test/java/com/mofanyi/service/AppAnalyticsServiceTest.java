package com.mofanyi.service;

import com.mofanyi.entity.App;
import com.mofanyi.entity.AppAnalyticsEvent;
import com.mofanyi.mapper.AppAnalyticsDailyMapper;
import com.mofanyi.mapper.AppAnalyticsEventMapper;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.dto.analytics.AnalyticsEventRequest;
import com.mofanyi.workspace.WorkspaceAclService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppAnalyticsServiceTest {

    @Test
    @DisplayName("业务事件写高基数明细并按日期/事件聚合")
    void recordsAndAggregatesAppEvent() {
        AppMapper appMapper = mock(AppMapper.class);
        AppAnalyticsEventMapper eventMapper = mock(AppAnalyticsEventMapper.class);
        AppAnalyticsDailyMapper dailyMapper = mock(AppAnalyticsDailyMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        App app = new App();
        app.setId(7L);
        app.setWorkspaceId(8L);
        app.setDeployKey("abc123");
        app.setCurrentRevision(55L);
        when(appMapper.selectOneById(7L)).thenReturn(app);
        when(eventMapper.insertSelective(any())).thenAnswer(invocation -> {
            AppAnalyticsEvent event = invocation.getArgument(0);
            event.setId(99L);
            return 1;
        });
        when(dailyMapper.increment(anyLong(), anyLong(), anyLong(), any(), anyString()))
                .thenReturn(1);
        AppAnalyticsService service = new AppAnalyticsService(
                appMapper, eventMapper, dailyMapper,
                mock(WorkspaceAclService.class), redis);
        AnalyticsEventRequest request = new AnalyticsEventRequest();
        request.setAppId(7L);
        request.setEventName("page_view");
        request.setPath("/gallery");

        assertTrue(service.record(request));
        verify(eventMapper).insertSelective(any());
        verify(dailyMapper).increment(eq(99L), eq(7L), eq(55L), any(), eq("page_view"));
    }
}
