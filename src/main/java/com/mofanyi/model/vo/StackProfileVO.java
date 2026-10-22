package com.mofanyi.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class StackProfileVO implements Serializable {

    private String id;
    private String displayName;
    private String compatibilityType;
    private List<String> capabilities = new ArrayList<>();
    private boolean available;
    private String unavailableReason;
    private List<List<String>> installCommands = new ArrayList<>();
    private List<List<String>> buildCommands = new ArrayList<>();
    private String outputDirectory;

    private static final long serialVersionUID = 1L;
}
