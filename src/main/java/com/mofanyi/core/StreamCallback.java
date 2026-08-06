package com.mofanyi.core;

import com.mofanyi.model.vo.AiCodeGenResultVO;

import java.util.function.Consumer;

/**
 * 流式生成回调，封装 token / 完成 / 失败三个时机。
 */
public class StreamCallback {

    /**
     * 每个 token 到达时调用
     */
    private final Consumer<String> onToken;

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
        this.onToken = onToken;
        this.onDone = onDone;
        this.onError = onError;
    }

    public void onToken(String token) {
        onToken.accept(token);
    }

    public void onDone(AiCodeGenResultVO vo) {
        onDone.accept(vo);
    }

    public void onError(Throwable error) {
        onError.accept(error);
    }
}
