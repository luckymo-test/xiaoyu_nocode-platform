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
@Table("app_artifact")
public class AppArtifact implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("revisionId")
    private Long revisionId;

    @Column("jobId")
    private Long jobId;

    @Column("appId")
    private Long appId;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("artifactType")
    private String artifactType;

    private String scope;

    @Column("ownerId")
    private Long ownerId;

    private String visibility;
    private String uri;
    private String sha256;

    @Column("sizeBytes")
    private Long sizeBytes;

    @Column("mimeType")
    private String mimeType;

    private String metadata;

    @Column("createTime")
    private LocalDateTime createTime;
}
