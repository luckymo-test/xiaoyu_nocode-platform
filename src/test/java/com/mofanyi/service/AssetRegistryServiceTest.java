package com.mofanyi.service;

import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.entity.LibraryAsset;
import com.mofanyi.entity.User;
import com.mofanyi.entity.Workspace;
import com.mofanyi.mapper.LibraryAssetMapper;
import com.mofanyi.mapper.LibraryAssetVariantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetRegistryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("图片素材按 Workspace+SHA 去重，并记录尺寸和 ORIGINAL variant")
    void storesImageAndDeduplicates() throws Exception {
        LibraryAssetMapper assetMapper = mock(LibraryAssetMapper.class);
        LibraryAssetVariantMapper variantMapper = mock(LibraryAssetVariantMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tempDir.toString());
        AssetRegistryService service = new AssetRegistryService(
                assetMapper, variantMapper, workspaceService, files);

        User user = new User();
        user.setId(3L);
        Workspace workspace = new Workspace();
        workspace.setId(9L);
        when(workspaceService.ensureDefaultWorkspace(user)).thenReturn(workspace);
        when(assetMapper.selectOneByQuery(any())).thenReturn(null);
        when(assetMapper.insertSelective(any())).thenAnswer(invocation -> {
            LibraryAsset asset = invocation.getArgument(0);
            asset.setId(77L);
            return 1;
        });
        when(variantMapper.insertSelective(any())).thenReturn(1);

        BufferedImage image = new BufferedImage(32, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", "image/png", out.toByteArray());

        var result = service.upload(null, "IMAGE", "private", "test",
                file, user);

        assertEquals(77L, result.getId());
        assertEquals(32, result.getWidth());
        assertEquals(20, result.getHeight());
        assertNotNull(result.getOriginalUri());
        verify(variantMapper).insertSelective(any());
    }
}
