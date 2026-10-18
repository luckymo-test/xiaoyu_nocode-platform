package com.mofanyi.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 探针：当前配置的模型/网关能不能**接收图片输入**（多模态）。
 *
 * <p>做法：本地生成一张 512x512 的测试图（白底 + 红圆 + 蓝字 "PROBE 42"），
 * 以 inline base64 的方式随 UserMessage 发过去，看模型能否正确描述。
 * 能描述出"红圆/蓝字/PROBE 42"= 真多模态；报 400/内容类型不支持 = 文本模型，需换模型或换网关。
 *
 * <p>默认不参与常规测试，显式开启：
 * <pre>mvn -o test -Dtest=ImageInputProbeTest -DcodegenProbe=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "codegenProbe", matches = "true")
class ImageInputProbeTest {

    @Autowired
    @Qualifier("openAiChatModel")
    private ChatModel chatModel;

    @Test
    @DisplayName("探针：模型能否识别内联图片")
    void probeInlineImage() throws Exception {
        byte[] jpeg = testImage();
        System.out.println("[image-probe] jpegBytes=" + jpeg.length);

        UserMessage message = UserMessage.builder()
                .text("这张图里有什么？只回答你看到的主要形状、颜色和文字，不要解释。")
                .media(new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(jpeg)))
                .build();
        try {
            ChatResponse response = chatModel.call(new Prompt(message));
            String answer = response == null || response.getResult() == null
                    ? "null" : response.getResult().getOutput().getText();
            System.out.println("[image-probe] SUPPORTED? answer=" + answer);
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("[image-probe] FAILED: " + e.getClass().getSimpleName()
                    + " | root=" + root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }

    private static byte[] testImage() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 512, 512);
            g.setColor(Color.RED);
            g.fillOval(96, 60, 320, 320);
            g.setColor(Color.BLUE);
            g.setFont(new Font("SansSerif", Font.BOLD, 64));
            g.drawString("PROBE 42", 70, 460);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
