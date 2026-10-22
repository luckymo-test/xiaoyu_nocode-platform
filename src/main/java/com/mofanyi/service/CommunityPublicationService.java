package com.mofanyi.service;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.entity.App;
import com.mofanyi.entity.AppPublication;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppPublicationMapper;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.model.dto.community.PublicationCreateRequest;
import com.mofanyi.model.vo.AppPublicationVO;
import com.mofanyi.workspace.WorkspaceAclService;
import com.mofanyi.workspace.WorkspacePermission;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CommunityPublicationService {

    private final AppPublicationMapper publicationMapper;
    private final AppMapper appMapper;
    private final AppRevisionMapper revisionMapper;
    private final WorkspaceAclService aclService;

    public CommunityPublicationService(AppPublicationMapper publicationMapper,
                                       AppMapper appMapper,
                                       AppRevisionMapper revisionMapper,
                                       WorkspaceAclService aclService) {
        this.publicationMapper = publicationMapper;
        this.appMapper = appMapper;
        this.revisionMapper = revisionMapper;
        this.aclService = aclService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AppPublicationVO create(PublicationCreateRequest request, User user) {
        ThrowUtils.throwIf(request == null || request.getAppId() == null,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(request.getTitle()), ErrorCode.PARAMS_ERROR,
                "发布标题不能为空");
        App app = appMapper.selectOneById(request.getAppId());
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        aclService.require(app.getWorkspaceId(), user.getId(), WorkspacePermission.PUBLISH);
        Long revisionId = request.getRevisionId() == null
                ? app.getCurrentRevision() : request.getRevisionId();
        ThrowUtils.throwIf(revisionId == null, ErrorCode.OPERATION_ERROR,
                "应用还没有可发布版本");
        AppRevision revision = revisionMapper.selectOneById(revisionId);
        ThrowUtils.throwIf(revision == null || !app.getId().equals(revision.getAppId()),
                ErrorCode.NOT_FOUND_ERROR, "发布版本不存在");
        ThrowUtils.throwIf(publicationMapper.selectOneByQuery(QueryWrapper.create()
                .eq("revisionId", revisionId).limit(1)) != null,
                ErrorCode.OPERATION_ERROR, "该版本已提交过发布");
        LocalDateTime now = LocalDateTime.now();
        AppPublication publication = AppPublication.builder()
                .appId(app.getId())
                .revisionId(revisionId)
                .workspaceId(app.getWorkspaceId())
                .publisherId(user.getId())
                .status("PENDING")
                .title(StrUtil.maxLength(request.getTitle(), 256))
                .description(StrUtil.maxLength(request.getDescription(), 2048))
                .license(StrUtil.maxLength(request.getLicense(), 256))
                .forkAllowed(Boolean.TRUE.equals(request.getForkAllowed()) ? 1 : 0)
                .createTime(now)
                .updateTime(now)
                .build();
        ThrowUtils.throwIf(publicationMapper.insertSelective(publication) == 0,
                ErrorCode.OPERATION_ERROR, "提交发布失败");
        return toVO(publication);
    }

    public List<AppPublicationVO> listPublished() {
        return publicationMapper.selectListByQuery(QueryWrapper.create()
                        .eq("status", "PUBLISHED")
                        .orderBy("publishedAt", false))
                .stream().map(this::toVO).toList();
    }

    public AppPublicationVO getPublished(Long publicationId) {
        ThrowUtils.throwIf(publicationId == null || publicationId <= 0, ErrorCode.PARAMS_ERROR);
        AppPublication publication = publicationMapper.selectOneById(publicationId);
        ThrowUtils.throwIf(publication == null || !"PUBLISHED".equals(publication.getStatus()),
                ErrorCode.NOT_FOUND_ERROR, "公开作品不存在");
        return toVO(publication);
    }

    @Transactional(rollbackFor = Exception.class)
    public AppPublicationVO review(Long publicationId, boolean approved, String note) {
        ThrowUtils.throwIf(publicationId == null || publicationId <= 0, ErrorCode.PARAMS_ERROR);
        AppPublication publication = publicationMapper.selectOneById(publicationId);
        ThrowUtils.throwIf(publication == null, ErrorCode.NOT_FOUND_ERROR, "发布记录不存在");
        ThrowUtils.throwIf(!"PENDING".equals(publication.getStatus()),
                ErrorCode.OPERATION_ERROR, "该发布不在待审核状态");
        publication.setStatus(approved ? "PUBLISHED" : "REJECTED");
        publication.setReviewNote(StrUtil.maxLength(note, 1024));
        publication.setPublishedAt(approved ? LocalDateTime.now() : null);
        publication.setUpdateTime(LocalDateTime.now());
        publicationMapper.update(publication);
        return toVO(publication);
    }

    public boolean takedown(Long publicationId, String note) {
        ThrowUtils.throwIf(publicationId == null || publicationId <= 0, ErrorCode.PARAMS_ERROR);
        AppPublication publication = publicationMapper.selectOneById(publicationId);
        ThrowUtils.throwIf(publication == null, ErrorCode.NOT_FOUND_ERROR, "发布记录不存在");
        publication.setStatus("TAKEDOWN");
        publication.setReviewNote(StrUtil.maxLength(note, 1024));
        publication.setUpdateTime(LocalDateTime.now());
        return publicationMapper.update(publication) == 1;
    }

    private AppPublicationVO toVO(AppPublication publication) {
        AppPublicationVO vo = new AppPublicationVO();
        vo.setId(publication.getId());
        vo.setAppId(publication.getAppId());
        vo.setRevisionId(publication.getRevisionId());
        vo.setWorkspaceId(publication.getWorkspaceId());
        vo.setPublisherId(publication.getPublisherId());
        vo.setStatus(publication.getStatus());
        vo.setTitle(publication.getTitle());
        vo.setDescription(publication.getDescription());
        vo.setLicense(publication.getLicense());
        vo.setForkAllowed(Integer.valueOf(1).equals(publication.getForkAllowed()));
        vo.setReviewNote(publication.getReviewNote());
        vo.setPublishedAt(publication.getPublishedAt());
        vo.setCreateTime(publication.getCreateTime());
        return vo;
    }
}
