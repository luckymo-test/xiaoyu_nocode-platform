package com.mofanyi.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mofanyi.entity.App;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 *  映射层。
 *
 * @author 程序员luckymo
 */
public interface AppMapper extends BaseMapper<App> {

    /**
     * 清空部署标识与部署时间（下线时置空，绕过 updateById 忽略 null 字段的限制）
     *
     * @param id 应用 id
     * @return 受影响行数
     */
    @Update("UPDATE app SET deployKey = NULL, deployedTime = NULL WHERE id = #{id}")
    int clearDeploy(@Param("id") Long id);

    /**
     * 更新封面图 URL（原图 + 压缩图）
     *
     * @param id               应用 id
     * @param cover            原图 URL
     * @param compressPictures 压缩图 URL
     * @return 受影响行数
     */
    @Update("UPDATE app SET cover = #{cover}, compressPictures = #{compressPictures} WHERE id = #{id}")
    int updateCover(@Param("id") Long id,
                    @Param("cover") String cover,
                    @Param("compressPictures") String compressPictures);
}
