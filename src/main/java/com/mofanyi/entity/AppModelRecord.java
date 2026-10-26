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

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_model_record")
public class AppModelRecord {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("workspaceId")
    private Long workspaceId;
    @Column("appId")
    private Long appId;
    @Column("modelName")
    private String modelName;
    @Column("recordId")
    private String recordId;
    @Column("dataJson")
    private String dataJson;
    @Column("recordVersion")
    private Long recordVersion;
    @Column("ownerId")
    private String ownerId;
    @Column("isSample")
    private Integer isSample;
    @Column("isDelete")
    private Integer isDelete;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
