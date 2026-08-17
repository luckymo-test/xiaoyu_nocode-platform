package com.mofanyi.ai.model;

import lombok.Data;

/**
 * Vue3 工程中的单个文件（路径 + 内容）。
 */
@Data
public class VueFileResult {

    /**
     * 文件路径（如 package.json、src/App.js）
     */
    private String path;

    /**
     * 文件内容
     */
    private String content;
}
