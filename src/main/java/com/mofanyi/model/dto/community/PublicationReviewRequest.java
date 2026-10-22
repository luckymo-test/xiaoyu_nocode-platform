package com.mofanyi.model.dto.community;

import lombok.Data;

import java.io.Serializable;

@Data
public class PublicationReviewRequest implements Serializable {

    private Boolean approved;
    private String reviewNote;

    private static final long serialVersionUID = 1L;
}
