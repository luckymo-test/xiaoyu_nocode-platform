package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AppPublicationVO implements Serializable {

    private Long id;
    private Long appId;
    private Long revisionId;
    private Long workspaceId;
    private Long publisherId;
    private String status;
    private String title;
    private String description;
    private String license;
    private boolean forkAllowed;
    private String reviewNote;
    private LocalDateTime publishedAt;
    private LocalDateTime createTime;

    private static final long serialVersionUID = 1L;
}
