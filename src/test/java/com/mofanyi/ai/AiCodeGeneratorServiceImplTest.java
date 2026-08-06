package com.mofanyi.ai;

import com.mofanyi.ai.model.HtmlCodeResult;
import com.mofanyi.ai.model.MultiFileCodeResult;
import com.mofanyi.core.CodeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCodeGeneratorServiceImplTest {

    private static final String SINGLE_HTML_PROMPT = "你是前端开发专家，请生成单个 HTML 文件。";
    private static final String MULTI_FILE_PROMPT = "你是全栈开发专家，请生成多文件项目。";

    private ChatModel chatModel;
    private AiCodeGeneratorServiceImpl service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        service = new AiCodeGeneratorServiceImpl(chatModel, SINGLE_HTML_PROMPT, MULTI_FILE_PROMPT, new CodeParser());
    }

    private void stubChatModelReturn(String jsonContent) {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(jsonContent))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void generateHtmlCode_shouldConvertJsonToResult() {
        stubChatModelReturn("""
                {"htmlCode":"<html><body>登录页面</body></html>","description":"这是一个登录页面"}""");

        HtmlCodeResult result = service.generateHtmlCode("生成一个登录页面");

        assertEquals("<html><body>登录页面</body></html>", result.getHtmlCode());
        assertEquals("这是一个登录页面", result.getDescription());
    }

    @Test
    void generateHtmlCode_shouldSendPromptWithSchemaInstructionAndUserMessage() {
        stubChatModelReturn("{\"htmlCode\":\"x\",\"description\":\"y\"}");

        service.generateHtmlCode("生成一个登录页面");

        String promptText = capturePromptText();
        assertTrue(promptText.contains(SINGLE_HTML_PROMPT));
        assertTrue(promptText.contains("【请根据以下用户需求生成网站：】"));
        assertTrue(promptText.contains("生成一个登录页面"));
        assertTrue(promptText.contains("htmlCode"));
        assertTrue(promptText.contains("description"));
    }

    @Test
    void generateMultiFileCode_shouldConvertJsonToResult() {
        stubChatModelReturn("""
                {"htmlCode":"<html>...</html>","cssCode":"body{}","jsCode":"console.log(1)","description":"用户管理系统"}""");

        MultiFileCodeResult result = service.generateMultiFileCode("生成一个用户管理系统");

        assertEquals("<html>...</html>", result.getHtmlCode());
        assertEquals("body{}", result.getCssCode());
        assertEquals("console.log(1)", result.getJsCode());
        assertEquals("用户管理系统", result.getDescription());
    }

    @Test
    void generateMultiFileCode_shouldSendPromptWithSchemaInstructionAndUserMessage() {
        stubChatModelReturn("{\"htmlCode\":\"h\",\"cssCode\":\"c\",\"jsCode\":\"j\",\"description\":\"d\"}");

        service.generateMultiFileCode("生成一个用户管理系统");

        String promptText = capturePromptText();
        assertTrue(promptText.contains(MULTI_FILE_PROMPT));
        assertTrue(promptText.contains("【请根据以下用户需求生成网站：】"));
        assertTrue(promptText.contains("生成一个用户管理系统"));
        assertTrue(promptText.contains("htmlCode"));
        assertTrue(promptText.contains("cssCode"));
        assertTrue(promptText.contains("jsCode"));
        assertTrue(promptText.contains("description"));
    }

    private String capturePromptText() {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue().getContents();
    }
}
