package com.mofanyi.core;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * Token 用量累加（plan-refactor 7.1 拆分第 S3 步，从 VueProjectAgent 抽出）。
 *
 * <p>注意本步<b>只搬 accumulate 方法，不动 runToolLoop 的签名</b>（仍传 {@code long[] usage}）。
 * 把它改成"传递累加器对象"是 S3 的第二半，会牵动 {@code generate} ↔ {@code runToolLoop} 的传参。
 */
@Component
public class TokenUsageAccumulator {

    /**
     * 累加单轮模型响应的 token 用量到 usage[0]=prompt、usage[1]=completion
     *
     * @param resp  单轮模型响应（取 metadata.usage）
     * @param usage 累加器数组；为 null 时直接返回
     */
    public void accumulate(ChatResponse resp, long[] usage) {
        if (usage == null || resp == null || resp.getMetadata() == null) {
            return;
        }
        var usageMeta = resp.getMetadata().getUsage();
        if (usageMeta == null) {
            return;
        }
        if (usageMeta.getPromptTokens() != null) {
            usage[0] += usageMeta.getPromptTokens();
        }
        if (usageMeta.getCompletionTokens() != null) {
            usage[1] += usageMeta.getCompletionTokens();
        }
    }

    /**
     * 单次生成的 token 用量（模型 usage 累加）——原为 VueProjectAgent 的内部 record，
     * S3-2 前半迁到这里，使"用量"概念与累加逻辑同处一类。
     */
    public record TokenUsage(long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
