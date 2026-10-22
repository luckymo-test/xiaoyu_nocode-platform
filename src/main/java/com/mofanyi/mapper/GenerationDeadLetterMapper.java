package com.mofanyi.mapper;

import com.mofanyi.entity.GenerationDeadLetter;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface GenerationDeadLetterMapper extends BaseMapper<GenerationDeadLetter> {

    @Update("""
            update generation_dead_letter
            set status = 'REPLAYED', replayedBy = #{userId},
                replayedAt = now(), updateTime = now()
            where jobId = #{jobId} and status = 'PENDING'
            """)
    int markReplayed(@Param("jobId") Long jobId, @Param("userId") Long userId);
}
