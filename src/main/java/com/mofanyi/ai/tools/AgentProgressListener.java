package com.mofanyi.ai.tools;

/**
 * 生成/修改过程的进度监听：每写一个文件回调一次，可转发思考增量。
 * 由 VueProjectAgent 的调用方实现（前端据此流式展示）。
 */
public interface AgentProgressListener {

    void onFileWritten(String path, int size, String content);

    /**
     * 模型思考过程增量（reasoning_content），默认空实现
     */
    default void onReasoning(String reasoning) {
    }
}
