package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TemplateBundleVO implements Serializable {

    private String id;
    private String version;
    private String displayName;
    private List<String> compatibleProfiles = new ArrayList<>();
    private List<String> sectionIds = new ArrayList<>();
    private Map<String, String> designTokens = new LinkedHashMap<>();
    private String promptFragment;
    private String effectiveHash;

    private static final long serialVersionUID = 1L;
}
