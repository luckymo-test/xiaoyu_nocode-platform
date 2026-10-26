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
@Table("app_deployment")
public class AppDeployment {
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;
    @Column("workspaceId")
    private Long workspaceId;
    @Column("appId")
    private Long appId;
    @Column("revisionId")
    private Long revisionId;
    @Column("deployKey")
    private String deployKey;
    private String url;
    private String status;
    @Column("changeSummary")
    private String changeSummary;
    @Column("createdBy")
    private Long createdBy;
    @Column("createTime")
    private LocalDateTime createTime;
    @Column("updateTime")
    private LocalDateTime updateTime;
}
