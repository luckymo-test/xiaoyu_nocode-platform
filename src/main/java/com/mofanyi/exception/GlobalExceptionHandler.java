package com.mofanyi.exception;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * SSE 超时通常已经提交 text/event-stream 响应，不能再按 JSON 改写响应体。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void asyncRequestTimeoutHandler(AsyncRequestTimeoutException e) {
        log.warn("异步流式请求超时，连接已结束：{}", e.getMessage());
    }
}

