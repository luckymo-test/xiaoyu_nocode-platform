package com.mofanyi.ai.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "codegen.model")
public class ModelProperties {

    private Gateway gateway = new Gateway();
    private Routing routing = new Routing();
    private Fallback fallback = new Fallback();
    private String defaultModel = "deepseek-flash";
    private Map<String, Definition> definitions = new LinkedHashMap<>();
    private Map<String, String> routes = new LinkedHashMap<>();
    private Map<String, List<String>> fallbacks = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Gateway {
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Routing {
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Fallback {
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Definition {
        private String provider;
        private String modelName;
        private int contextWindow = 32_000;
        private int maxOutputTokens = 4_096;
        private List<ModelCapability> supports = List.of();
        private String reasoningStyle = "none";
        private boolean enabled = true;
    }
}
