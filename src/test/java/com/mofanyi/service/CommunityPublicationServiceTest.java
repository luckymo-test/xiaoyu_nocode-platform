package com.mofanyi.service;

import com.mofanyi.entity.App;
import com.mofanyi.entity.AppPublication;
import com.mofanyi.entity.AppRevision;
import com.mofanyi.entity.User;
import com.mofanyi.mapper.AppMapper;
import com.mofanyi.mapper.AppPublicationMapper;
import com.mofanyi.mapper.AppRevisionMapper;
import com.mofanyi.model.dto.community.PublicationCreateRequest;
import com.mofanyi.workspace.WorkspaceAclService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityPublicationServiceTest {

    @Test
    @DisplayName("社区发布绑定不可变 Revision 并进入 PENDING 审核")
    void createsPendingPublicationForRevision() {
        AppPublicationMapper publicationMapper = mock(AppPublicationMapper.class);
        AppMapper appMapper = mock(AppMapper.class);
        AppRevisionMapper revisionMapper = mock(AppRevisionMapper.class);
        WorkspaceAclService aclService = mock(WorkspaceAclService.class);
        App app = new App();
        app.setId(7L);
        app.setWorkspaceId(8L);
        app.setCurrentRevision(55L);
        when(appMapper.selectOneById(7L)).thenReturn(app);
        AppRevision revision = new AppRevision();
        revision.setId(55L);
        revision.setAppId(7L);
        when(revisionMapper.selectOneById(55L)).thenReturn(revision);
        when(publicationMapper.selectOneByQuery(any())).thenReturn(null);
        when(publicationMapper.insertSelective(any())).thenAnswer(invocation -> {
            AppPublication publication = invocation.getArgument(0);
            publication.setId(99L);
            return 1;
        });
        CommunityPublicationService service = new CommunityPublicationService(
                publicationMapper, appMapper, revisionMapper, aclService);
        User user = new User();
        user.setId(3L);
        PublicationCreateRequest request = new PublicationCreateRequest();
        request.setAppId(7L);
        request.setTitle("摄影作品集");
        request.setLicense("CC-BY-4.0");

        var result = service.create(request, user);

        assertEquals(99L, result.getId());
        assertEquals(55L, result.getRevisionId());
        assertEquals("PENDING", result.getStatus());
    }
}
