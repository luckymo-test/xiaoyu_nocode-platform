package com.mofanyi.model.vo;

import com.mofanyi.entity.AppArtifact;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppRevisionVO implements Serializable {

    private Long id;
    private Long appId;
    private Long workspaceId;
    private Long jobId;
    private Long revisionNo;
    private Long baseRevisionId;
    private String status;
    private String codeGenType;
    private String templateBundleId;
    private String templateVersion;
    private String templateHash;
    private List<String> skillIds = new ArrayList<>();
    private String sourceUri;
    private String validationStatus;
    private Long createdBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createTime;
    private List<AppArtifact> artifacts = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
