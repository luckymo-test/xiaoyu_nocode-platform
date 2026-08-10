package com.mofanyi.model.dto.app;

import lombok.Data;

import java.io.Serializable;

/**
 * 部署应用请求
 */
@Data
public class AppDeployRequest implements Serializable {

    /**
     * 应用 id
     */
    private Long appId;

    private static final long serialVersionUID = 1L;
}
