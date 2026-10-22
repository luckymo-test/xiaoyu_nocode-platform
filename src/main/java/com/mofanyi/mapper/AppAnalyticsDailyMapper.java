package com.mofanyi.mapper;

import com.mofanyi.entity.AppAnalyticsDaily;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface AppAnalyticsDailyMapper extends BaseMapper<AppAnalyticsDaily> {

    @Insert("""
            insert into app_analytics_daily
                (id, appId, revisionId, statDate, eventName, eventCount)
            values
                (#{id}, #{appId}, #{revisionId}, #{statDate}, #{eventName}, 1)
            on duplicate key update
                eventCount = eventCount + 1,
                updateTime = now()
            """)
    int increment(@Param("id") Long id,
                  @Param("appId") Long appId,
                  @Param("revisionId") Long revisionId,
                  @Param("statDate") LocalDate statDate,
                  @Param("eventName") String eventName);
}
