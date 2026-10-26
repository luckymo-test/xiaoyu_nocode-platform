package com.mofanyi.ai.model;

import java.util.Locale;

public enum ModelTask {
    GENERATE,
    REPAIR,
    MODIFY,
    VISION_CURATE,
    QUERY_PLAN,
    COMPRESS,
    RESEARCH,
    REQUIREMENT,
    ARCHITECT,
    UI,
    PLAN;

    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
