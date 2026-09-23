package com.info.platform.interfaces.common;

import com.info.platform.domain.common.ErrorCode;
import org.slf4j.MDC;

/**
 * 统一响应体：{@code {code, msg, data, traceId}}。
 *
 * <p>code/msg/data 为业务字段；traceId 从 MDC 取（由 {@code TraceIdFilter} 在请求入口写入）。 对齐技术方案 §4.1：0 成功、1xxx
 * 认证、2xxx 参数、3xxx 业务、5xxx 服务端。
 */
public class Result<T> {

    private final int code;
    private final String msg;
    private final T data;
    private final String traceId;

    public Result(int code, String msg, T data, String traceId) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data, currentTraceId());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null, currentTraceId());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        return new Result<>(
                errorCode.getCode(),
                detail != null ? detail : errorCode.getMsg(),
                null,
                currentTraceId());
    }

    /**
     * 失败响应 + 携带业务数据（M5：30068 待确认占位符移除需回传 removed/unknown 清单供前端渲染勾选，T45）。
     *
     * <p>全局异常处理器不适用（其 {@code Result<Void>} 无 data），此形态仅供控制器对结构化业务错误直返。
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String detail, T data) {
        return new Result<>(
                errorCode.getCode(),
                detail != null ? detail : errorCode.getMsg(),
                data,
                currentTraceId());
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
