package com.mofanyi.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(
        name = "codegen.security.spring-security.enabled",
        havingValue = "true"
)
public class MethodSecurityConfig {
}
