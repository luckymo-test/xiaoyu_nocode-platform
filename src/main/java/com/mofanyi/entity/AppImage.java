package com.mofanyi.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用嵌入图片实体：记录生成网页时嵌入的真实图片（1:N）。
 *
 * @author 程序员luckymo
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_image")
public class AppImage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 应用id
     */
    @Column("appId")
    private Long appId;

    /**
     * 图片 COS URL
     */
    @Column("imageUrl")
    private String imageUrl;

    /**
     * 用途: main/embed
     */
    @Column("imageType")
    private String imageType;

    /**
     * 排序
     */
    @Column("sortOrder")
    private Integer sortOrder;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
