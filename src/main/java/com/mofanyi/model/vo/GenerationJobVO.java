package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class GenerationJobVO implements Serializable {

    private Long jobId;
    private Long workspaceId;
    private Long appId;
    private Long requestedBy;
    private String requestType;
    private String status;
    private String currentStage;
    private String codeGenType;
    private String stackProfileId;
    private String templateBundleId;
    private String templateVersion;
    private String templateHash;
    private List<String> skillIds = new ArrayList<>();
    private String visibility;
    private Integer attempt;
    private Integer maxAttempts;
    private String errorType;
    private String errorMessage;
    private String eventsUrl;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
