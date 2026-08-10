package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * 代码生成结果转换器。
 * 将模型输出的原始文本转换为结构化结果对象，并提供对应的 JSON Schema 指令（format）。
 */
@Component
public class CodeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BeanOutputConverter<HtmlCodeResult> htmlCodeConverter;
    private final BeanOutputConverter<MultiFileCodeResult> multiFileCodeConverter;

    public CodeParser() {
        this.htmlCodeConverter = new BeanOutputConverter<>(HtmlCodeResult.class);
        this.multiFileCodeConverter = new BeanOutputConverter<>(MultiFileCodeResult.class);
    }

    /**
     * 将模型文本转换为单文件 HTML 结果
     *
     * @param text 模型输出的原始文本（JSON）
     * @return 单文件 HTML 结果
     */
    public HtmlCodeResult parseHtmlCode(String text) {
        try {
            return htmlCodeConverter.convert(text);
        } catch (RuntimeException e) {
            // 模型输出可能被 max_tokens 截断，尝试容错解析
            return parseHtmlCodeLenient(text);
        }
    }

    /**
     * 将模型文本转换为多文件结果
     *
     * @param text 模型输出的原始文本（JSON）
     * @return 多文件结果
     */
    public MultiFileCodeResult parseMultiFileCode(String text) {
        try {
            return multiFileCodeConverter.convert(text);
        } catch (RuntimeException e) {
            return parseMultiFileCodeLenient(text);
        }
    }

    /**
     * 容错解析单文件 HTML：模型输出被截断时，用 JSON 树 + 正则提取字段。
     * 先尝试解析已到达的 JSON 前缀，失败则逐字段正则提取。
     */
    private HtmlCodeResult parseHtmlCodeLenient(String text) {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setDescription(extractField(text, "description"));
        result.setHtmlCode(extractField(text, "htmlCode"));
        return result;
    }

    /**
     * 容错解析多文件结果
     */
    private MultiFileCodeResult parseMultiFileCodeLenient(String text) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setDescription(extractField(text, "description"));
        result.setHtmlCode(extractField(text, "htmlCode"));
        result.setCssCode(extractField(text, "cssCode"));
        result.setJsCode(extractField(text, "jsCode"));
        return result;
    }

    /**
     * 线性扫描提取字符串字段值。字段未出现返回 null；闭合返回真实值；
     * 被截断（未闭合）返回已到达部分（手动反转义）。
     */
    private String extractField(String text, String field) {
        String marker = "\"" + field + "\":";
        int markerIdx = text.indexOf(marker);
        if (markerIdx == -1) {
            return null;
        }
        int start = text.indexOf('"', markerIdx + marker.length());
        if (start == -1) {
            return null;
        }
        start++;
        StringBuilder sb = new StringBuilder();
        int i = start;
        boolean closed = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    sb.append('\\').append(next);
                    i += 2;
                    continue;
                }
                break;
            }
            if (c == '"') {
                closed = true;
                break;
            }
            sb.append(c);
            i++;
        }
        String raw = sb.toString();
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        // 尝试反序列化转义序列（针对闭合的字段）
        if (closed) {
            try {
                JsonNode node = MAPPER.readTree("\"" + raw + "\"");
                return node.asText();
            } catch (Exception ignore) {
                // 未闭合或转义异常，按原始字符串处理
            }
        }
        // 未闭合：替换常见转义为真实字符
        return raw
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\\", "\\");
    }

    /**
     * 获取单文件模式的 JSON Schema 指令，用于拼接进 prompt
     */
    public String getHtmlFormat() {
        return htmlCodeConverter.getFormat();
    }

    /**
     * 获取多文件模式的 JSON Schema 指令，用于拼接进 prompt
     */
    public String getMultiFileFormat() {
        return multiFileCodeConverter.getFormat();
    }
}
