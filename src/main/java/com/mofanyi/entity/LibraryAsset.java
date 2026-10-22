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
@Table("app_asset")
public class LibraryAsset implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("workspaceId")
    private Long workspaceId;

    @Column("ownerId")
    private Long ownerId;

    @Column("assetType")
    private String assetType;

    @Column("sourceType")
    private String sourceType;

    @Column("displayName")
    private String displayName;

    @Column("originalUri")
    private String originalUri;

    private String sha256;

    @Column("mimeType")
    private String mimeType;

    private String extension;

    @Column("sizeBytes")
    private Long sizeBytes;

    private Integer width;
    private Integer height;

    @Column("durationMs")
    private Long durationMs;

    private String license;
    private String visibility;

    @Column("moderationStatus")
    private String moderationStatus;

    private String metadata;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
