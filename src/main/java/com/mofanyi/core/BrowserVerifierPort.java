package com.mofanyi.core;

import com.mofanyi.model.enums.CodeGenTypeEnum;

/**
 * 浏览器渲染验证端口。Selenium、Playwright Worker 和后续远端执行器都实现这一契约。
 */
public interface BrowserVerifierPort {

    RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId);

    RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId, String stage);

    default RenderVerifier.RenderReport verify(CodeGenTypeEnum type, Long appId,
                                               String stage, Long jobId) {
        return verify(type, appId, stage);
    }

    void persistReport(CodeGenTypeEnum type, Long appId,
                       RenderVerifier.RenderReport report, String stage);
}
