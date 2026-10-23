package com.mofanyi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Component
@ConfigurationProperties(prefix = "codegen.security")
public class SecurityProperties {

    private final SpringSecurity springSecurity = new SpringSecurity();
    private final Csrf csrf = new Csrf();
    private final Cors cors = new Cors();

    @Getter
    @Setter
    public static class SpringSecurity {
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Csrf {
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:5199",
                "http://127.0.0.1:5199"
        ));
    }
}
