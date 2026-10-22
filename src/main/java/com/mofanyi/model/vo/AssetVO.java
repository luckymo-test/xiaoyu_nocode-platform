package com.mofanyi.model.vo;

import com.mofanyi.entity.LibraryAssetVariant;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AssetVO implements Serializable {

    private Long id;
    private Long workspaceId;
    private Long ownerId;
    private String assetType;
    private String sourceType;
    private String displayName;
    private String originalUri;
    private String sha256;
    private String mimeType;
    private String extension;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private String license;
    private String visibility;
    private String moderationStatus;
    private LocalDateTime createTime;
    private List<LibraryAssetVariant> variants = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
