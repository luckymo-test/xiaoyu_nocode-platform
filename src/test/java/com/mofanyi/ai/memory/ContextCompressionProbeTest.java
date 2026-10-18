package com.mofanyi.ai.memory;

import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型上下文压缩探针。默认跳过：
 * {@code mvn -o test -Dtest=ContextCompressionProbeTest -DcodegenProbe=true}
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ContextCompressionProbeTest {

    private static final long APP_ID = 990001000000000003L;

    @Autowired
    private ContextCompressionService compressionService;

    @Autowired
    private com.mofanyi.core.CodeFileWriteService codeFileWriteService;

    @Test
    @DisplayName("探针：真实模型生成结构化旧对话摘要并保留最近四轮")
    void compressesWithRealModel() {
        ReflectionTestUtils.setField(compressionService, "enabled", true);
        ReflectionTestUtils.setField(compressionService, "minTokens", 1);
        ReflectionTestUtils.setField(compressionService, "keepRecentRounds", 4);

        List<Message> raw = new ArrayList<>();
        raw.add(new UserMessage("做一个小玉摄影工作室官网，整体深色、留白充足。"));
        raw.add(new AssistantMessage("已完成首版首页。"));
        raw.add(new UserMessage("主色改成墨绿色，不要高饱和。"));
        raw.add(new AssistantMessage("已改为墨绿色主题。"));
        raw.add(new UserMessage("用户否决了蓝紫渐变，后续不要再使用。"));
        raw.add(new AssistantMessage("已记录，后续不使用蓝紫渐变。"));
        raw.add(new UserMessage("作品区改成两列瀑布流。"));
        raw.add(new AssistantMessage("已改为两列瀑布流。"));
        raw.add(new UserMessage("标题字体再大一点。"));
        raw.add(new AssistantMessage("已调整标题字号。"));
        raw.add(new UserMessage("增加联系表单。"));
        raw.add(new AssistantMessage("已增加联系表单。"));
        raw.add(new UserMessage("表单提交后显示成功提示。"));
        raw.add(new AssistantMessage("已增加成功提示。"));
        raw.add(new UserMessage("移动端检查一下横向溢出。"));
        raw.add(new AssistantMessage("移动端已检查。"));

        List<Message> compressed = compressionService.compressIfNeeded(
                CodeGenTypeEnum.HTML, APP_ID, raw);

        assertEquals(9, compressed.size(), "1 条摘要 + 最近 4 轮原文");
        assertTrue(compressed.get(0) instanceof SystemMessage);
        assertTrue(compressed.get(0).getText().contains("较早对话摘要"));
        assertTrue(compressed.get(0).getText().contains("约束"));
        assertTrue(compressed.get(1).getText().contains("标题字体再大一点"));
        Path cache = Path.of(codeFileWriteService.getProjectDir(CodeGenTypeEnum.HTML, APP_ID))
                .resolve(".agent").resolve("context-summary.json");
        assertTrue(Files.exists(cache));
        System.out.println("[context-compression-probe] summary=" + compressed.get(0).getText()
                + "\ncompressedMessages=" + compressed.size());
    }
}
