package com.mofanyi.ai;

import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.RenderVerifier;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不依赖图片/COS的真实生成探针，用于验证 AgentToolLoop 抽离后主链路。
 * {@code mvn -o test -Dtest=GenerationNoImageProbeTest -DcodegenProbe=true}
 */
@SpringBootTest(properties = {
        "pexels.api-key=",
        "pixabay.api-key=",
        "codegen.verify.enabled=false"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class GenerationNoImageProbeTest {

    private static final long PROBE_APP_ID = 990001000000000002L;

    @Autowired
    private VueProjectAgent vueProjectAgent;

    @Autowired
    private CodeFileWriteService codeFileWriteService;

    @Autowired
    private RenderVerifier renderVerifier;

    @Test
    @DisplayName("探针：标准推理的真实 HTML 生成与渲染验证")
    void generatesHtmlWithoutImages() {
        long appId = Long.getLong("probe.appId", PROBE_APP_ID);
        Path projectDir = Path.of(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, appId));
        boolean reuseGeneratedProject = Files.exists(projectDir.resolve("index.html"));
        List<String> written = new ArrayList<>();
        VueProjectAgent.GenerateResult generated = null;
        if (!reuseGeneratedProject) {
            generated = vueProjectAgent.generate(
                    CodeGenTypeEnum.HTML,
                    appId,
                    "生成一个纯 CSS 的个人摄影作品集首页，深色背景，包含姓名、简介、三项作品简介和联系邮箱，"
                            + "不加载任何外部图片，不引用任何图片 URL。",
                    AiCallContext.of(appId, null, "probe"),
                    new VueProjectAgent.VueProgressListener() {
                        @Override
                        public void onFileWritten(String path, int size, String content) {
                            written.add(path);
                        }
                    },
                    ReasoningLevel.STANDARD);
        }

        AiCodeGenResultVO result = codeFileWriteService.readProjectCode(
                CodeGenTypeEnum.HTML, appId);
        assertTrue(reuseGeneratedProject || !written.isEmpty(), "应至少写入一个文件");
        assertTrue(result != null && result.getHtmlCode() != null && !result.getHtmlCode().isBlank(),
                "应产出 index.html");
        RenderVerifier.RenderReport report = renderVerifier.verify(CodeGenTypeEnum.HTML, appId);
        renderVerifier.persistReport(CodeGenTypeEnum.HTML, appId, report, "probe");
        System.out.println("[generation-no-image-probe] appId=" + appId + " written=" + written
                + " usage=" + (generated == null ? "reused" : generated.getUsage())
                + " verify=" + report.summary());
    }
}
