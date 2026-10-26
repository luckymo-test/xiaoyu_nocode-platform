package com.mofanyi.security;

import com.mofanyi.constant.UserConstant;
import com.mofanyi.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Redis-backed fixed-window rate limit. Redis failure is fail-open for
 * availability; generation quotas and SQL constraints remain authoritative.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redis;

    @Value("${codegen.rate-limit.enabled:true}")
    private boolean enabled;

    public ApiRateLimitFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()
                && path.startsWith(request.getContextPath())) {
            path = path.substring(request.getContextPath().length());
        }
        String method = request.getMethod();
        if (!shouldLimit(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }
        int limit = limit(path);
        String actor = actor(request);
        String window = String.valueOf(System.currentTimeMillis() / 60_000L);
        String key = "codegen:rate:" + limitKey(path) + ":" + actor + ":" + window;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofSeconds(70));
            }
            if (count != null && count > limit) {
                response.setStatus(429);
                response.setCharacterEncoding("UTF-8");
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"code\":42900,\"data\":null,\"message\":\"请求过于频繁，请稍后重试\"}");
                return;
            }
        } catch (RuntimeException ignored) {
            // Rate-limit infrastructure outage must not take down the control plane.
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldLimit(String path, String method) {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        if (path.startsWith("/runtime/")) {
            return true;
        }
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
    }

    private int limit(String path) {
        if (path.contains("/user/login") || path.contains("/user/register")) {
            return 20;
        }
        if (path.contains("/generation-jobs")) {
            return 10;
        }
        if (path.contains("/files/content")) {
            return 60;
        }
        if (path.startsWith("/runtime/")) {
            return 300;
        }
        if (path.contains("/shares")) {
            return 30;
        }
        return 120;
    }

    private String limitKey(String path) {
        if (path.contains("/user/login")) return "login";
        if (path.contains("/user/register")) return "register";
        if (path.contains("/generation-jobs")) return "jobs";
        if (path.contains("/files/content")) return "files";
        if (path.startsWith("/runtime/")) return "runtime";
        if (path.contains("/shares")) return "shares";
        return "api";
    }

    private String actor(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(UserConstant.USER_LOGIN_STATE);
        if (value instanceof User user && user.getId() != null) {
            return "user:" + user.getId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        return "ip:" + ip;
    }
}
