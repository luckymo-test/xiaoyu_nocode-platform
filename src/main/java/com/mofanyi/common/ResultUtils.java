package com.mofanyi.common;
import com.mofanyi.exception.ErrorCode;

public class ResultUtils {

    /**
     * 成功
     *
     * @param data 数据
     * @param <T>  数据类型
     * @return 响应
     */
    public static <T> com.mofanyi.common.BaseResponse<T> success(T data) {
        return new com.mofanyi.common.BaseResponse<>(0, data, "ok");
    }

    /**
     * 失败
     *
     * @param errorCode 错误码
     * @return 响应
     */
    public static com.mofanyi.common.BaseResponse<?> error(ErrorCode errorCode) {
        return new com.mofanyi.common.BaseResponse<>(errorCode);
    }

    /**
     * 失败
     *
     * @param code    错误码
     * @param message 错误信息
     * @return 响应
     */
    public static com.mofanyi.common.BaseResponse<?> error(int code, String message) {
        return new com.mofanyi.common.BaseResponse<>(code, null, message);
    }

    /**
     * 失败
     *
     * @param errorCode 错误码
     * @return 响应
     */
    public static com.mofanyi.common.BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new com.mofanyi.common.BaseResponse<>(errorCode.getCode(), null, message);
    }
}
