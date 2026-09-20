package com.info.platform.domain.common;

/**
 * 业务异常（可预期，携带 {@link ErrorCode}）。领域层抛出、接口层全局拦截。
 *
 * <p>纯 Java（继承 RuntimeException），不依赖框架，领域层/应用层均可抛出。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMsg(), null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail != null ? detail : errorCode.getMsg(), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }
}
