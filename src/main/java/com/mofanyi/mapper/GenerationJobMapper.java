package com.mofanyi.mapper;

import com.mofanyi.entity.GenerationJob;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface GenerationJobMapper extends BaseMapper<GenerationJob> {

    @Update("""
            update generation_job
            set status = 'RUNNING', currentStage = 'GENERATE',
                attempt = attempt + 1, leaseOwner = #{owner},
                leaseExpiresAt = #{expiresAt}, fencingToken = fencingToken + 1,
                updateTime = now()
            where id = #{jobId} and status in ('QUEUED', 'WAITING_RETRY')
            """)
    int claim(@Param("jobId") Long jobId,
              @Param("owner") String owner,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            update generation_job
            set leaseExpiresAt = #{expiresAt}, updateTime = now()
            where id = #{jobId} and status = 'RUNNING'
              and leaseOwner = #{owner} and fencingToken = #{fencingToken}
            """)
    int renewLease(@Param("jobId") Long jobId,
                   @Param("owner") String owner,
                   @Param("fencingToken") Long fencingToken,
                   @Param("expiresAt") LocalDateTime expiresAt);

    @Update("""
            update generation_job
            set status = 'QUEUED', currentStage = 'RECOVERED',
                leaseOwner = null, leaseExpiresAt = null, updateTime = now()
            where id = #{jobId} and status = 'RUNNING'
              and leaseOwner = #{owner} and fencingToken = #{fencingToken}
              and leaseExpiresAt < now()
            """)
    int recoverExpired(@Param("jobId") Long jobId,
                       @Param("owner") String owner,
                       @Param("fencingToken") Long fencingToken);

    @Select("select nextEventSeq from generation_job where id = #{jobId} for update")
    Long lockNextEventSeq(@Param("jobId") Long jobId);

    @Update("""
            update generation_job
            set nextEventSeq = #{nextSeq}, currentStage = coalesce(#{stage}, currentStage),
                updateTime = now()
            where id = #{jobId}
            """)
    int updateNextEventSeq(@Param("jobId") Long jobId,
                           @Param("nextSeq") Long nextSeq,
                           @Param("stage") String stage);

    @Update("""
            update generation_job
            set status = #{status}, currentStage = coalesce(#{stage}, currentStage),
                leaseOwner = null, leaseExpiresAt = null,
                errorType = #{errorType}, errorMessage = #{errorMessage},
                updateTime = now()
            where id = #{jobId} and fencingToken = #{fencingToken}
              and status in ('RUNNING', 'CANCEL_REQUESTED')
            """)
    int finish(@Param("jobId") Long jobId,
               @Param("fencingToken") Long fencingToken,
               @Param("status") String status,
               @Param("stage") String stage,
               @Param("errorType") String errorType,
               @Param("errorMessage") String errorMessage);

    @Update("""
            update generation_job
            set status = 'CANCELLED', currentStage = 'CANCELLED',
                leaseOwner = null, leaseExpiresAt = null, updateTime = now()
            where id = #{jobId} and status = 'QUEUED'
            """)
    int cancelQueued(@Param("jobId") Long jobId);

    @Update("""
            update generation_job
            set status = 'CANCELLED', currentStage = 'CANCELLED',
                leaseOwner = null, leaseExpiresAt = null,
                errorType = 'CANCELLED', errorMessage = '用户取消生成', updateTime = now()
            where id = #{jobId} and status = 'CANCEL_REQUESTED'
            """)
    int finishCancelRequested(@Param("jobId") Long jobId);

    @Update("""
            update generation_job
            set candidateRevisionId = #{candidateRevisionId},
                publishedRevisionId = #{publishedRevisionId}, updateTime = now()
            where id = #{jobId} and fencingToken = #{fencingToken}
            """)
    int recordPublishedRevision(@Param("jobId") Long jobId,
                                @Param("fencingToken") Long fencingToken,
                                @Param("candidateRevisionId") Long candidateRevisionId,
                                @Param("publishedRevisionId") Long publishedRevisionId);

    @Update("""
            update generation_job
            set status = 'WAITING_RETRY', currentStage = 'WAITING_RETRY',
                leaseOwner = null, leaseExpiresAt = null,
                errorType = #{errorType}, errorMessage = #{errorMessage}, updateTime = now()
            where id = #{jobId} and fencingToken = #{fencingToken}
              and status in ('RUNNING', 'CANCEL_REQUESTED')
            """)
    int scheduleRetry(@Param("jobId") Long jobId,
                      @Param("fencingToken") Long fencingToken,
                      @Param("errorType") String errorType,
                      @Param("errorMessage") String errorMessage);

    @Update("""
            update generation_job
            set status = 'QUEUED', currentStage = 'RETRY_REQUESTED',
                attempt = 0, leaseOwner = null, leaseExpiresAt = null,
                errorType = null, errorMessage = null, updateTime = now()
            where id = #{jobId} and status = 'FAILED'
            """)
    int requeueFailed(@Param("jobId") Long jobId);

    @Update("""
            update generation_job
            set status = 'CANCEL_REQUESTED', updateTime = now()
            where id = #{jobId} and status in ('QUEUED', 'RUNNING', 'WAITING_RETRY')
            """)
    int requestCancel(@Param("jobId") Long jobId);
}
