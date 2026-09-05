package com.mofanyi.core;

import com.mofanyi.model.vo.AiCodeGenResultVO;

import java.util.function.Consumer;

/**
 * 流式生成回调，封装 token / reasoning / 完成 / 失败四个时机。
 */
public class StreamCallback {

    /**
     * 每个 token 到达时调用
     */
    private final Consumer<String> onToken;

    /**
     * 模型思考过程增量到达时调用（reasoning_content），可为 null
     */
    private final Consumer<String> onReasoning;

    /**
     * 全部生成完成时调用
     */
    private final Consumer<AiCodeGenResultVO> onDone;

    /**
     * 处理失败时调用
     */
    private final Consumer<Throwable> onError;

    public StreamCallback(Consumer<String> onToken,
                          Consumer<AiCodeGenResultVO> onDone,
                          Consumer<Throwable> onError) {
        this(onToken, null, onDone, onError);
    }

    public StreamCallback(Consumer<String> onToken,
                          Consumer<String> onReasoning,
                          Consumer<AiCodeGenResultVO> onDone,
                          Consumer<Throwable> onError) {
        this.onToken = onToken;
        this.onReasoning = onReasoning;
        this.onDone = onDone;
        this.onError = onError;
    }

    public void onToken(String token) {
        onToken.accept(token);
    }

    public void onReasoning(String reasoning) {
        if (onReasoning != null) {
            onReasoning.accept(reasoning);
        }
    }

    public void onDone(AiCodeGenResultVO vo) {
        onDone.accept(vo);
    }

    public void onError(Throwable error) {
        onError.accept(error);
    }
}
