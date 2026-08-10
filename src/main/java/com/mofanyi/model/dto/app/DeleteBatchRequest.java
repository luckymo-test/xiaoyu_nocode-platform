package com.mofanyi.model.dto.app;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量删除/恢复应用请求
 */
@Data
public class DeleteBatchRequest implements Serializable {

    /**
     * 应用 id 集合
     */
    private List<Long> ids;

    private static final long serialVersionUID = 1L;
}
