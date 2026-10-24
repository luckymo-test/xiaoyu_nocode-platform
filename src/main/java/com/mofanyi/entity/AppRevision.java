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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_revision")
public class AppRevision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("appId")
    private Long appId;

    @Column("jobId")
    private Long jobId;

    @Column("revisionNo")
    private Long revisionNo;

    @Column("baseRevisionId")
    private Long baseRevisionId;

    private String status;

    @Column("codeGenType")
    private String codeGenType;

    @Column("stackProfileVersion")
    private String stackProfileVersion;

    @Column("templateBundleId")
    private String templateBundleId;

    @Column("templateVersion")
    private String templateVersion;

    @Column("templateHash")
    private String templateHash;

    @Column("skillsJson")
    private String skillsJson;

    @Column("skillsSnapshotJson")
    private String skillsSnapshotJson;

    @Column("sourceUri")
    private String sourceUri;

    private String manifest;

    @Column("validationStatus")
    private String validationStatus;

    @Column("createdBy")
    private Long createdBy;

    @Column("publishedAt")
    private LocalDateTime publishedAt;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
