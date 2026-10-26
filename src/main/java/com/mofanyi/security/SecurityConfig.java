package com.mofanyi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.config.SecurityProperties;
import com.mofanyi.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String SECURITY_FLAG = "codegen.security.spring-security.enabled";

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    @ConditionalOnProperty(name = SECURITY_FLAG, havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain legacySecurityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = SECURITY_FLAG, havingValue = "true")
    public SecurityFilterChain securedFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            CsrfTokenRepository csrfTokenRepository,
            SecurityProperties properties,
            SessionAuthenticationFilter sessionAuthenticationFilter,
            ObjectMapper objectMapper) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> {
                    if (!properties.getCsrf().isEnabled()) {
                        csrf.disable();
                        return;
                    }
                    csrf.csrfTokenRepository(csrfTokenRepository)
                            .csrfTokenRequestHandler(csrfRequestHandler)
                            .ignoringRequestMatchers("/v1/analytics/events");
                })
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(accessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/user/login", "/user/register",
                                "/v1/analytics/events").permitAll()
                        .requestMatchers("/runtime/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/health/", "/error",
                                "/v1/security/csrf",
                                "/v1/templates", "/v1/stack-profiles", "/v1/skills",
                                "/v1/project-templates",
                                "/v1/evaluation/prompts",
                                "/v1/community/publications",
                                "/v1/community/publications/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(sessionAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
                response, HttpServletResponse.SC_UNAUTHORIZED,
                ErrorCode.NOT_LOGIN_ERROR, objectMapper);
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeError(
                response, HttpServletResponse.SC_FORBIDDEN,
                ErrorCode.NO_AUTH_ERROR, objectMapper);
    }

    private void writeError(HttpServletResponse response,
                            int status,
                            ErrorCode errorCode,
                            ObjectMapper objectMapper) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ResultUtils.error(errorCode));
    }
}
