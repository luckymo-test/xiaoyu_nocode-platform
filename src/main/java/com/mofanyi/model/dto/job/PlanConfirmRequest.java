package com.mofanyi.model.dto.job;

import lombok.Data;

@Data
public class PlanConfirmRequest {
    private String planHash;
    private String comment;
}
