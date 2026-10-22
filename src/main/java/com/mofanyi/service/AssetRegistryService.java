package com.mofanyi.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.LibraryAsset;
import com.mofanyi.entity.LibraryAssetVariant;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.LibraryAssetMapper;
import com.mofanyi.mapper.LibraryAssetVariantMapper;
import com.mofanyi.model.vo.AssetVO;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Workspace 素材库：内容寻址、按 SHA-256 去重，生成链仍可继续使用原图片服务。
 */
@Service
public class AssetRegistryService {

    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ASSET_TYPES = Set.of(
            "IMAGE", "VIDEO", "AUDIO", "MODEL_3D", "FONT", "OTHER");
    private static final Set<String> VISIBILITIES = Set.of("private", "team", "public");

    private final LibraryAssetMapper assetMapper;
    private final LibraryAssetVariantMapper variantMapper;
    private final WorkspaceService workspaceService;
    private final CodeFileWriteService codeFileWriteService;

    public AssetRegistryService(LibraryAssetMapper assetMapper,
                                LibraryAssetVariantMapper variantMapper,
                                WorkspaceService workspaceService,
                                CodeFileWriteService codeFileWriteService) {
        this.assetMapper = assetMapper;
        this.variantMapper = variantMapper;
        this.workspaceService = workspaceService;
        this.codeFileWriteService = codeFileWriteService;
    }

    public AssetVO upload(Long workspaceId, String requestedType, String visibility,
                          String license, MultipartFile file, User user) {
        ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(file.getSize() > MAX_SIZE_BYTES, ErrorCode.PARAMS_ERROR,
                "单个素材不能超过 10MB");
        Long targetWorkspace = workspaceId != null && workspaceId > 0
                ? workspaceId : workspaceService.ensureDefaultWorkspace(user).getId();
        workspaceService.requireMember(targetWorkspace, user.getId());
        String assetType = resolveAssetType(requestedType, file.getContentType());
        String targetVisibility = StrUtil.blankToDefault(visibility, "private");
        ThrowUtils.throwIf(!VISIBILITIES.contains(targetVisibility),
                ErrorCode.PARAMS_ERROR, "素材可见范围不合法");
        try {
            byte[] bytes = file.getBytes();
            String sha256 = sha256(bytes);
            LibraryAsset existing = assetMapper.selectOneByQuery(QueryWrapper.create()
                    .eq("workspaceId", targetWorkspace)
                    .eq("assetType", assetType)
                    .eq("sha256", sha256)
                    .limit(1));
            if (existing != null) {
                return toVO(existing);
            }

            String extension = resolveExtension(file.getOriginalFilename(), file.getContentType());
            Path root = Paths.get(codeFileWriteService.getTmpRoot(), "assets",
                    String.valueOf(targetWorkspace)).toAbsolutePath().normalize();
            Path target = root.resolve(sha256 + (extension.isBlank() ? "" : "." + extension));
            FileUtil.mkParentDirs(target.toFile());
            FileUtil.writeBytes(bytes, target.toFile());

            LocalDateTime now = LocalDateTime.now();
            Integer[] dimensions = imageDimensions(bytes, assetType);
            LibraryAsset asset = LibraryAsset.builder()
                    .workspaceId(targetWorkspace)
                    .ownerId(user.getId())
                    .assetType(assetType)
                    .sourceType("UPLOAD")
                    .displayName(StrUtil.blankToDefault(file.getOriginalFilename(), "未命名素材"))
                    .originalUri(target.toUri().toString())
                    .sha256(sha256)
                    .mimeType(StrUtil.blankToDefault(file.getContentType(), "application/octet-stream"))
                    .extension(extension)
                    .sizeBytes((long) bytes.length)
                    .width(dimensions[0])
                    .height(dimensions[1])
                    .durationMs(null)
                    .license(license)
                    .visibility(targetVisibility)
                    .moderationStatus("APPROVED")
                    .metadata("{}")
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            ThrowUtils.throwIf(assetMapper.insertSelective(asset) == 0 || asset.getId() == null,
                    ErrorCode.OPERATION_ERROR, "保存素材失败");
            LibraryAssetVariant original = LibraryAssetVariant.builder()
                    .assetId(asset.getId())
                    .variantType("ORIGINAL")
                    .uri(asset.getOriginalUri())
                    .format(extension)
                    .width(asset.getWidth())
                    .height(asset.getHeight())
                    .sizeBytes(asset.getSizeBytes())
                    .createTime(now)
                    .build();
            variantMapper.insertSelective(original);
            return toVO(asset);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取上传素材失败", e);
        }
    }

    public List<AssetVO> list(Long workspaceId, String assetType, User user) {
        ThrowUtils.throwIf(user == null || user.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        Long targetWorkspace = workspaceId != null && workspaceId > 0
                ? workspaceId : workspaceService.ensureDefaultWorkspace(user).getId();
        workspaceService.requireMember(targetWorkspace, user.getId());
        QueryWrapper query = QueryWrapper.create()
                .eq("workspaceId", targetWorkspace)
                .eq("assetType", StrUtil.blankToDefault(assetType, null))
                .orderBy("createTime", false);
        return assetMapper.selectListByQuery(query).stream().map(this::toVO).toList();
    }

    public AssetVO get(Long assetId, User user) {
        ThrowUtils.throwIf(assetId == null || assetId <= 0, ErrorCode.PARAMS_ERROR);
        LibraryAsset asset = assetMapper.selectOneById(assetId);
        ThrowUtils.throwIf(asset == null, ErrorCode.NOT_FOUND_ERROR, "素材不存在");
        workspaceService.requireMember(asset.getWorkspaceId(), user.getId());
        return toVO(asset);
    }

    private AssetVO toVO(LibraryAsset asset) {
        AssetVO vo = new AssetVO();
        vo.setId(asset.getId());
        vo.setWorkspaceId(asset.getWorkspaceId());
        vo.setOwnerId(asset.getOwnerId());
        vo.setAssetType(asset.getAssetType());
        vo.setSourceType(asset.getSourceType());
        vo.setDisplayName(asset.getDisplayName());
        vo.setOriginalUri(asset.getOriginalUri());
        vo.setSha256(asset.getSha256());
        vo.setMimeType(asset.getMimeType());
        vo.setExtension(asset.getExtension());
        vo.setSizeBytes(asset.getSizeBytes());
        vo.setWidth(asset.getWidth());
        vo.setHeight(asset.getHeight());
        vo.setDurationMs(asset.getDurationMs());
        vo.setLicense(asset.getLicense());
        vo.setVisibility(asset.getVisibility());
        vo.setModerationStatus(asset.getModerationStatus());
        vo.setCreateTime(asset.getCreateTime());
        vo.setVariants(variantMapper.selectListByQuery(QueryWrapper.create()
                .eq("assetId", asset.getId()).orderBy("variantType", true)));
        return vo;
    }

    private String resolveAssetType(String requested, String mimeType) {
        String type = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) {
            String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
            if (mime.startsWith("image/")) type = "IMAGE";
            else if (mime.startsWith("video/")) type = "VIDEO";
            else if (mime.startsWith("audio/")) type = "AUDIO";
            else if (mime.contains("font")) type = "FONT";
            else type = "OTHER";
        }
        ThrowUtils.throwIf(!ASSET_TYPES.contains(type), ErrorCode.PARAMS_ERROR, "素材类型不合法");
        return type;
    }

    private String resolveExtension(String filename, String mimeType) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("jpeg")) return "jpg";
        if (mime.contains("png")) return "png";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("mp4")) return "mp4";
        if (mime.contains("mpeg")) return "mp3";
        return "";
    }

    private Integer[] imageDimensions(byte[] bytes, String assetType) {
        if (!"IMAGE".equals(assetType)) {
            return new Integer[]{null, null};
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null
                    ? new Integer[]{null, null}
                    : new Integer[]{image.getWidth(), image.getHeight()};
        } catch (Exception ignored) {
            return new Integer[]{null, null};
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("计算素材摘要失败", e);
        }
    }
}
