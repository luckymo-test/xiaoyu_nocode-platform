package com.mofanyi.core;

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
        return htmlCodeConverter.convert(text);
    }

    /**
     * 将模型文本转换为多文件结果
     *
     * @param text 模型输出的原始文本（JSON）
     * @return 多文件结果
     */
    public MultiFileCodeResult parseMultiFileCode(String text) {
        return multiFileCodeConverter.convert(text);
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
