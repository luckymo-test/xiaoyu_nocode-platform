package com.mofanyi.mapper;

import com.mofanyi.entity.GenerationOutbox;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface GenerationOutboxMapper extends BaseMapper<GenerationOutbox> {

    @Update("""
            update generation_outbox
            set status = 'DISPATCHING', updateTime = now()
            where id = #{id} and status = 'PENDING' and nextAttemptAt <= now()
            """)
    int tryClaim(@Param("id") Long id);

    @Update("""
            update generation_outbox
            set status = #{status}, retryCount = retryCount + 1,
                nextAttemptAt = date_add(now(), interval #{delaySeconds} second),
                updateTime = now()
            where id = #{id}
            """)
    int markStatus(@Param("id") Long id, @Param("status") String status,
                   @Param("delaySeconds") int delaySeconds);

    @Update("""
            update generation_outbox
            set status = 'PENDING', updateTime = now()
            where status = 'DISPATCHING'
              and updateTime < date_sub(now(), interval 2 minute)
            """)
    int resetStaleDispatching();
}
