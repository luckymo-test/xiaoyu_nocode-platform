package com.mofanyi.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路真实生成探针（会实际调用模型、图库、COS，默认跳过）。
 * {@code mvn -o test -Dtest=GenerationPipelineProbeTest -DcodegenProbe=true}
 */
@SpringBootTest(properties = {
        "codegen.materials.curator.enabled=true",
        "codegen.verify.enabled=false"
})
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
@EnabledIfSystemProperty(named = "codegenProbeCos", matches = "true")
class GenerationPipelineProbeTest {

    private static final long PROBE_APP_ID = 990001000000000001L;
    private static final Pattern IMAGE_URL = Pattern.compile(
            "https?://[^\\s\"'()<>]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\\s\"'()<>]*)?",
            Pattern.CASE_INSENSITIVE);

    @Autowired
    private VueProjectAgent vueProjectAgent;

    @Autowired
    private CodeFileWriteService codeFileWriteService;

    @Autowired
    private RenderVerifier renderVerifier;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("探针：Curator + 标准推理 + HTML 真实生成 + 3 档渲染验证")
    void generatesWithCuratedMaterials() throws Exception {
        List<String> written = new ArrayList<>();
        VueProjectAgent.GenerateResult generated = vueProjectAgent.generate(
                CodeGenTypeEnum.HTML,
                PROBE_APP_ID,
                "生成一个现代汉服摄影工作室官网。需要人物主视觉、作品画廊和竖版卡片图，"
                        + "东方审美，柔和自然光，页面信息完整，适合直接预览。",
                AiCallContext.of(PROBE_APP_ID, null, "probe"),
                new VueProjectAgent.VueProgressListener() {
                    @Override
                    public void onFileWritten(String path, int size, String content) {
                        written.add(path);
                    }
                },
                ReasoningLevel.STANDARD);

        Path projectDir = Path.of(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, PROBE_APP_ID));
        Path materialsFile = projectDir.resolve(".agent").resolve("materials.json");
        assertTrue(Files.exists(materialsFile), "Curator 开启时应落盘 materials.json");
        JsonNode materials = objectMapper.readTree(materialsFile.toFile());
        JsonNode selected = materials.path("selected");
        assertTrue(materials.path("curatorUsed").asBoolean(), "Curator 开启时不应退回程序规则");
        assertTrue(selected.isArray() && !selected.isEmpty(),
                "Curator 应根据页面规模自主输出可用图片");
        Set<String> selectedUrls = java.util.stream.StreamSupport.stream(selected.spliterator(), false)
                .map(node -> node.path("url").asText(""))
                .filter(url -> !url.isBlank())
                .collect(Collectors.toSet());

        AiCodeGenResultVO result = codeFileWriteService.readProjectCode(
                CodeGenTypeEnum.HTML, PROBE_APP_ID);
        String html = result == null ? "" : result.getHtmlCode();
        Matcher matcher = IMAGE_URL.matcher(html == null ? "" : html);
        int imageCount = 0;
        while (matcher.find()) {
            imageCount++;
            assertTrue(selectedUrls.contains(matcher.group()), "页面图片不在 selected 集合内：" + matcher.group());
        }

        RenderVerifier.RenderReport report = renderVerifier.verify(CodeGenTypeEnum.HTML, PROBE_APP_ID);
        renderVerifier.persistReport(CodeGenTypeEnum.HTML, PROBE_APP_ID, report, "probe");
        System.out.println("[generation-probe] written=" + written
                + " selected=" + selectedUrls.size()
                + " pageImages=" + imageCount
                + " usage=" + generated.getUsage()
                + " verify=" + report.summary());
        assertFalse(written.isEmpty(), "应至少写入一个文件");
    }
}
