package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class SkillDefinitionVO implements Serializable {

    private String id;
    private String version;
    private String displayName;
    private List<String> compatibleProfiles = new ArrayList<>();
    private List<String> allowedTools = new ArrayList<>();
    private List<String> permissions = new ArrayList<>();
    private String promptFragment;
    private List<String> validationRules = new ArrayList<>();
    private List<String> conflictsWith = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
