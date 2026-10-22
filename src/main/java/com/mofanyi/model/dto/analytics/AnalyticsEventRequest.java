package com.mofanyi.model.dto.analytics;

import lombok.Data;

import java.io.Serializable;

@Data
public class AnalyticsEventRequest implements Serializable {

    private Long appId;
    private Long revisionId;
    private String eventName;
    private String sessionId;
    private String path;
    private String referrer;
    private String deviceType;
    private String region;
    private String metadata;

    private static final long serialVersionUID = 1L;
}
