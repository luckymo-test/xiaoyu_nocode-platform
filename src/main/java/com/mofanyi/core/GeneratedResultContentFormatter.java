package com.mofanyi.core;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import org.springframework.stereotype.Component;

/**
 * 把生成结果转为聊天历史中的 Markdown，供旧接口和持久化 Job Runner 共用。
 */
@Component
public class GeneratedResultContentFormatter {

    public String format(AiCodeGenResultVO vo) {
        if (vo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(vo.getDescription())) {
            sb.append(vo.getDescription());
        }
        if (vo.getFiles() != null && !vo.getFiles().isEmpty()) {
            for (VueFileResult file : vo.getFiles()) {
                if (StrUtil.isNotBlank(file.getPath()) && file.getContent() != null) {
                    sb.append("\n\n#### ").append(file.getPath()).append("\n```js\n")
                            .append(file.getContent()).append("\n```");
                }
            }
            return sb.toString();
        }
        if (StrUtil.isNotBlank(vo.getHtmlCode())) {
            sb.append("\n\n#### index.html\n```html\n").append(vo.getHtmlCode()).append("\n```");
        }
        if (StrUtil.isNotBlank(vo.getCssCode())) {
            sb.append("\n\n#### css/styles.css\n```css\n").append(vo.getCssCode()).append("\n```");
        }
        if (StrUtil.isNotBlank(vo.getJsCode())) {
            sb.append("\n\n#### js/app.js\n```javascript\n").append(vo.getJsCode()).append("\n```");
        }
        return sb.toString();
    }
}
