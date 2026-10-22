package com.mofanyi.model.dto.community;

import lombok.Data;

import java.io.Serializable;

@Data
public class PublicationCreateRequest implements Serializable {

    private Long appId;
    private Long revisionId;
    private String title;
    private String description;
    private String license;
    private Boolean forkAllowed;

    private static final long serialVersionUID = 1L;
}
