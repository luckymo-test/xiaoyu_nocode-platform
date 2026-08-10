package com.mofanyi.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 精选审核请求
 */
@Data
public class AppFeatureAuditRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long id;

    /**
     * 是否通过（true 推荐，false 拒绝）
     */
    private Boolean approved;

    private static final long serialVersionUID = 1L;
}
