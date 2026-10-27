package com.mofanyi.model.vo;

import cn.hutool.core.bean.BeanUtil;
import com.mofanyi.entity.App;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVOTest {

    @Test
    void copiesDeploymentStateFromEntity() {
        LocalDateTime deployedAt = LocalDateTime.of(2026, 10, 27, 21, 30);
        App app = App.builder()
                .deployKey("abc123")
                .deployedTime(deployedAt)
                .build();
        AppVO vo = new AppVO();

        BeanUtil.copyProperties(app, vo);

        assertEquals("abc123", vo.getDeployKey());
        assertEquals(deployedAt, vo.getDeployedTime());
    }
}
