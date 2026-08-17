package com.mofanyi.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mofanyi.entity.AppImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 应用嵌入图片映射层。
 *
 * @author 程序员luckymo
 */
public interface AppImageMapper extends BaseMapper<AppImage> {

    /**
     * 物理删除某应用的全部嵌入图片记录（重新生成前清理旧记录）
     *
     * @param appId 应用 id
     * @return 受影响行数
     */
    @Delete("DELETE FROM app_image WHERE appId = #{appId}")
    int deleteByAppId(@Param("appId") Long appId);
}
