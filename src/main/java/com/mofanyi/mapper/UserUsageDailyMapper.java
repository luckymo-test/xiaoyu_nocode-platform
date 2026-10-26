package com.mofanyi.mapper;

import com.mofanyi.entity.UserUsageDaily;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserUsageDailyMapper extends BaseMapper<UserUsageDaily> {

    @Update("""
            insert into user_usage_daily
                (id, userId, statDate, generationCount, promptTokens, completionTokens,
                 estimatedCostMicros, storageBytes)
            values (#{id}, #{userId}, #{statDate}, #{generationCount}, #{promptTokens},
                    #{completionTokens}, #{estimatedCostMicros}, #{storageBytes})
            on duplicate key update
                generationCount = generationCount + values(generationCount),
                promptTokens = promptTokens + values(promptTokens),
                completionTokens = completionTokens + values(completionTokens),
                estimatedCostMicros = estimatedCostMicros + values(estimatedCostMicros),
                storageBytes = values(storageBytes),
                updateTime = now()
            """)
    int addUsage(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("statDate") java.time.LocalDate statDate,
                 @Param("generationCount") Long generationCount,
                 @Param("promptTokens") Long promptTokens,
                 @Param("completionTokens") Long completionTokens,
                 @Param("estimatedCostMicros") Long estimatedCostMicros,
                 @Param("storageBytes") Long storageBytes);
}
