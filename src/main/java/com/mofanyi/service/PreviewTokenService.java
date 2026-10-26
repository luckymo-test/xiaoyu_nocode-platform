package com.mofanyi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class PreviewTokenService {

    public record PreviewAccess(String url, Instant expiresAt) {
    }

    private final AppAuthService appAuthService;
    private final ObjectMapper objectMapper;

    @Value("${codegen.preview.gateway-url:http://localhost:8088}")
    private String gatewayUrl;

    @Value("${codegen.preview.signing-secret:dev-preview-secret-change-me}")
    private String signingSecret;

    @Value("${codegen.preview.ttl-seconds:900}")
    private long ttlSeconds;

    public PreviewTokenService(AppAuthService appAuthService, ObjectMapper objectMapper) {
        this.appAuthService = appAuthService;
        this.objectMapper = objectMapper;
    }

    public PreviewAccess issue(Long appId, User user) {
        App app = appAuthService.requireCanView(appId, user);
        long expires = Instant.now().plusSeconds(Math.max(60, ttlSeconds)).getEpochSecond();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of(
                    "appId", String.valueOf(appId),
                    "type", app.getCodeGenType(),
                    "userId", String.valueOf(user.getId()),
                    "exp", expires));
        } catch (Exception e) {
            throw new IllegalStateException("生成预览凭证失败", e);
        }
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String token = payload + "." + sign(payload);
        String base = gatewayUrl.endsWith("/")
                ? gatewayUrl.substring(0, gatewayUrl.length() - 1) : gatewayUrl;
        return new PreviewAccess(base + "/preview/" + token + "/", Instant.ofEpochSecond(expires));
    }

    private String sign(String payload) {
        ThrowUtils.throwIf(signingSecret == null || signingSecret.length() < 16,
                ErrorCode.SYSTEM_ERROR, "预览签名密钥未配置");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名预览凭证失败", e);
        }
    }
}
