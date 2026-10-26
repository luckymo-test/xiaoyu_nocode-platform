package com.mofanyi.model.dto.job;

import lombok.Data;

@Data
public class PlanReviseRequest {
    private String planHash;
    private String comment;
}
