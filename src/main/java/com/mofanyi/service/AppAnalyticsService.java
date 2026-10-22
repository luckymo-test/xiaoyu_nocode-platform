package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppAnalyticsEvent;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppAnalyticsDailyMapper;
import com.mofanyi.mapper.AppAnalyticsEventMapper;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.model.dto.analytics.AnalyticsEventRequest;
import com.mofanyi.model.vo.AnalyticsDailyVO;
import com.mofanyi.workspace.WorkspaceAclService;
import com.mofanyi.workspace.WorkspacePermission;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class AppAnalyticsService {

    private static final Set<String> EVENT_NAMES = Set.of(
            "page_view", "session_start", "interaction", "form_submit",
            "cta_click", "conversion", "error", "resource_failure");
    private static final int MAX_EVENTS_PER_APP_PER_MINUTE = 1000;

    private final AppMapper appMapper;
    private final AppAnalyticsEventMapper eventMapper;
    private final AppAnalyticsDailyMapper dailyMapper;
    private final WorkspaceAclService aclService;
    private final StringRedisTemplate redisTemplate;

    public AppAnalyticsService(AppMapper appMapper,
                               AppAnalyticsEventMapper eventMapper,
                               AppAnalyticsDailyMapper dailyMapper,
                               WorkspaceAclService aclService,
                               StringRedisTemplate redisTemplate) {
        this.appMapper = appMapper;
        this.eventMapper = eventMapper;
        this.dailyMapper = dailyMapper;
        this.aclService = aclService;
        this.redisTemplate = redisTemplate;
    }

    public boolean record(AnalyticsEventRequest request) {
        ThrowUtils.throwIf(request == null || request.getAppId() == null
                || request.getAppId() <= 0, ErrorCode.PARAMS_ERROR);
        String eventName = StrUtil.blankToDefault(request.getEventName(), "").trim();
        ThrowUtils.throwIf(!EVENT_NAMES.contains(eventName), ErrorCode.PARAMS_ERROR,
                "不支持的分析事件");
        App app = appMapper.selectOneById(request.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        ThrowUtils.throwIf(StrUtil.isBlank(app.getDeployKey()), ErrorCode.OPERATION_ERROR,
                "应用尚未部署");
        checkRateLimit(app.getId());

        LocalDateTime now = LocalDateTime.now();
        Long revisionId = request.getRevisionId() == null
                ? app.getCurrentRevision() : request.getRevisionId();
        long databaseRevisionId = revisionId == null ? 0L : revisionId;
        AppAnalyticsEvent event = AppAnalyticsEvent.builder()
                .appId(app.getId())
                .workspaceId(app.getWorkspaceId())
                .revisionId(revisionId)
                .eventName(eventName)
                .sessionId(StrUtil.maxLength(request.getSessionId(), 128))
                .path(StrUtil.maxLength(request.getPath(), 1024))
                .referrer(StrUtil.maxLength(request.getReferrer(), 1024))
                .deviceType(StrUtil.maxLength(request.getDeviceType(), 32))
                .region(StrUtil.maxLength(request.getRegion(), 64))
                .metadata(StrUtil.maxLength(request.getMetadata(), 4000))
                .eventTime(now)
                .createTime(now)
                .build();
        eventMapper.insertSelective(event);
        dailyMapper.increment(event.getId(), app.getId(),
                databaseRevisionId, now.toLocalDate(), eventName);
        return true;
    }

    public List<AnalyticsDailyVO> daily(Long appId, LocalDate from, LocalDate to,
                                        Long userId) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        App app = appMapper.selectOneById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        aclService.require(app.getWorkspaceId(), userId, WorkspacePermission.VIEW);
        LocalDate start = from == null ? LocalDate.now().minusDays(30) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        ThrowUtils.throwIf(start.isAfter(end), ErrorCode.PARAMS_ERROR, "日期范围不合法");
        return dailyMapper.selectListByQuery(QueryWrapper.create()
                        .eq("appId", appId)
                        .ge("statDate", start)
                        .le("statDate", end)
                        .orderBy("statDate", true)
                        .orderBy("eventName", true))
                .stream()
                .map(row -> new AnalyticsDailyVO(row.getStatDate(), row.getRevisionId(),
                        row.getEventName(), row.getEventCount()))
                .toList();
    }

    private void checkRateLimit(Long appId) {
        long minute = System.currentTimeMillis() / 60_000L;
        String key = "analytics:rate:" + appId + ":" + minute;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(70));
        }
        ThrowUtils.throwIf(count != null && count > MAX_EVENTS_PER_APP_PER_MINUTE,
                ErrorCode.OPERATION_ERROR, "分析事件写入过于频繁");
    }
}
