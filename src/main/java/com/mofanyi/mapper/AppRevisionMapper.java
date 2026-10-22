package com.mofanyi.mapper;

import com.mofanyi.entity.AppRevision;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AppRevisionMapper extends BaseMapper<AppRevision> {

    @Select("select coalesce(max(revisionNo), 0) from app_revision where appId = #{appId}")
    long nextRevisionNo(@Param("appId") Long appId);

    @Update("""
            update app_revision
            set status = 'SUPERSEDED', updateTime = now()
            where appId = #{appId} and status = 'PUBLISHED' and id <> #{revisionId}
            """)
    int supersedePublished(@Param("appId") Long appId, @Param("revisionId") Long revisionId);
}
