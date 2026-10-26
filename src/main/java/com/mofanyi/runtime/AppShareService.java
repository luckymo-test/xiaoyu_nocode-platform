package com.mofanyi.runtime;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppShareLink;
import com.mofanyi.entity.AppVisitor;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppShareLinkMapper;
import com.mofanyi.mapper.AppVisitorMapper;
import com.mofanyi.service.AppAuthService;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AppShareService {

    public record CreatedShare(Long shareId, String token, String url, LocalDateTime expiresAt) {
    }

    public record ExchangedVisitor(Long shareId, String visitorId, String role) {
    }

    private final AppMapper appMapper;
    private final AppShareLinkMapper shareMapper;
    private final AppVisitorMapper visitorMapper;
    private final AppAuthService appAuthService;
    private final ObjectMapper objectMapper;

    public AppShareService(AppMapper appMapper,
                           AppShareLinkMapper shareMapper,
                           AppVisitorMapper visitorMapper,
                           AppAuthService appAuthService,
                           ObjectMapper objectMapper) {
        this.appMapper = appMapper;
        this.shareMapper = shareMapper;
        this.visitorMapper = visitorMapper;
        this.appAuthService = appAuthService;
        this.objectMapper = objectMapper;
    }

    public CreatedShare create(Long appId, User user, Integer expiresHours,
                               List<String> models) {
        App app = appAuthService.requireCanPublish(appId, user);
        int hours = expiresHours == null ? 168 : Math.max(1, Math.min(24 * 365, expiresHours));
        String token = randomToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(hours);
        Map<String, Object> permissions = Map.of(
                "role", "CLIENT",
                "models", models == null || models.isEmpty() ? List.of("*") : List.copyOf(models),
                "actions", List.of("READ", "CREATE", "UPDATE"));
        AppShareLink share = AppShareLink.builder()
                .workspaceId(app.getWorkspaceId())
                .appId(appId)
                .tokenHash(sha256(token))
                .permissionsJson(write(permissions))
                .expiresAt(expiresAt)
                .createdBy(user.getId())
                .createTime(LocalDateTime.now())
                .build();
        shareMapper.insertSelective(share);
        String url = "/runtime/v1/apps/" + appId + "/sessions?share=" + token;
        return new CreatedShare(share.getId(), token, url, expiresAt);
    }

    public void revoke(Long appId, Long shareId, User user) {
        appAuthService.requireCanPublish(appId, user);
        AppShareLink share = shareMapper.selectOneById(shareId);
        ThrowUtils.throwIf(share == null || !appId.equals(share.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "分享链接不存在");
        share.setRevokedAt(LocalDateTime.now());
        shareMapper.update(share);
    }

    public ExchangedVisitor exchange(Long appId, String token) {
        ThrowUtils.throwIf(StrUtil.isBlank(token), ErrorCode.NO_AUTH_ERROR, "缺少分享 Token");
        AppShareLink share = validShare(appId, token);
        AppVisitor visitor = AppVisitor.builder()
                .workspaceId(share.getWorkspaceId())
                .appId(appId)
                .shareId(share.getId())
                .visitorId(UUID.randomUUID().toString())
                .lastSeenAt(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .build();
        visitorMapper.insertSelective(visitor);
        return new ExchangedVisitor(share.getId(), visitor.getVisitorId(), "CLIENT");
    }

    public AppRuntimeIdentity visitor(Long appId, String visitorId) {
        if (StrUtil.isBlank(visitorId)) {
            return null;
        }
        AppVisitor visitor = visitorMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("visitorId", visitorId)
                .limit(1));
        if (visitor == null) {
            return null;
        }
        AppShareLink share = shareMapper.selectOneById(visitor.getShareId());
        if (share == null || share.getRevokedAt() != null
                || (share.getExpiresAt() != null && share.getExpiresAt().isBefore(LocalDateTime.now()))) {
            return null;
        }
        visitor.setLastSeenAt(LocalDateTime.now());
        visitorMapper.update(visitor);
        return new AppRuntimeIdentity(visitor.getWorkspaceId(), appId,
                "visitor:" + visitor.getVisitorId(), "CLIENT", share.getId(), true);
    }

    public String permissionsJson(AppShareLink share) {
        return share.getPermissionsJson();
    }

    private AppShareLink validShare(Long appId, String token) {
        AppShareLink share = shareMapper.selectOneByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("tokenHash", sha256(token))
                .limit(1));
        ThrowUtils.throwIf(share == null, ErrorCode.NO_AUTH_ERROR, "分享链接无效");
        ThrowUtils.throwIf(share.getRevokedAt() != null, ErrorCode.NO_AUTH_ERROR, "分享链接已撤销");
        ThrowUtils.throwIf(share.getExpiresAt() != null
                        && share.getExpiresAt().isBefore(LocalDateTime.now()),
                ErrorCode.NO_AUTH_ERROR, "分享链接已过期");
        return share;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算分享 Token 摘要失败", e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化分享权限失败", e);
        }
    }
}
