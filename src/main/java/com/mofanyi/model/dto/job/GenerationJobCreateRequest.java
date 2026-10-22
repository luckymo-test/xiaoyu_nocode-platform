package com.mofanyi.model.dto.job;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建生成任务请求。当前先兼容 HTML / MultiFile / Vue，后续由 StackProfile 扩展。
 */
@Data
public class GenerationJobCreateRequest implements Serializable {

    private String message;
    private String reasoningLevel;
    private String codeGenType;
    private String stackProfileId;
    private String templateHint;
    private String templateBundleId;
    private String templateVersion;
    private String templateHash;
    private List<String> skills = new ArrayList<>();
    private String requestType;
    private String visibility;

    private static final long serialVersionUID = 1L;
}
