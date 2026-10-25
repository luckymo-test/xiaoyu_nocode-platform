package com.mofanyi.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.ModelGateway;
import com.mofanyi.ai.model.ModelRequest;
import com.mofanyi.ai.model.ModelResponse;
import com.mofanyi.ai.model.ModelTask;
import com.mofanyi.ai.model.ModelUsage;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageBlueprintServiceTest {

    @Test
    @DisplayName("编码前生成带版块与图片用途的页面蓝图")
    void createsPageBlueprint() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any())).thenReturn(new ModelResponse(
                """
                        ```json
                        {"visualDirection":"编辑杂志","signature":"跨栏标题",
                         "typography":"衬线标题配无衬线正文","palette":"暖纸色与深红",
                         "motion":"轻微淡入","sections":[
                           {"id":"hero","goal":"说明主题","layout":"左右非对称",
                            "density":"low","imagePurpose":"主视觉","imageRatio":"4:5"}
                         ]}
                        ```
                        """,
                "", List.of(), "stop", new ModelUsage(120, 80),
                "deepseek", "deepseek-flash", "deepseek-flash"));
        PageBlueprintService service = new PageBlueprintService(gateway, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);

        PageBlueprintService.Plan plan = service.plan(
                CodeGenTypeEnum.VUE, 7L, "摄影作品集", "template photography",
                "素材 1", AiCallContext.of(7L, 1L, "vue"));

        assertTrue(plan.promptSection().contains("页面蓝图"));
        assertTrue(plan.promptSection().contains("跨栏标题"));
        assertTrue(plan.promptSection().contains("\"imagePurpose\":\"主视觉\""));
        assertEquals(200, plan.usage().totalTokens());

        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(ModelRequest.class);
        verify(gateway).chat(request.capture());
        assertEquals(ModelTask.QUERY_PLAN, request.getValue().task());
        assertTrue(request.getValue().tools().isEmpty());
    }

    @Test
    @DisplayName("蓝图输出非法时自动降级且不阻塞生成")
    void invalidBlueprintFallsBack() {
        ModelGateway gateway = mock(ModelGateway.class);
        when(gateway.chat(any())).thenReturn(new ModelResponse(
                "not-json", "", List.of(), "stop", new ModelUsage(1, 1),
                "deepseek", "deepseek-flash", "deepseek-flash"));
        PageBlueprintService service = new PageBlueprintService(gateway, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);

        PageBlueprintService.Plan plan = service.plan(
                CodeGenTypeEnum.HTML, 7L, "企业介绍", "", "",
                AiCallContext.of(7L, 1L, "html"));

        assertEquals("", plan.promptSection());
        assertEquals(0, plan.usage().totalTokens());
    }
}
