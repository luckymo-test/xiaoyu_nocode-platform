package com.mofanyi.ai.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Vue3 工程生成结果：需求理解 + 文件清单。
 */
@Data
public class VueProjectResult {

    /**
     * 需求理解与整体方案描述
     */
    private String description;

    /**
     * 工程文件清单（路径 + 内容）
     */
    private List<VueFileResult> files = new ArrayList<>();
}
