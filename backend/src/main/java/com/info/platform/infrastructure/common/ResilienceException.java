package com.info.platform.infrastructure.common;

/**
 * 弹性调用失败（超时 / 重试耗尽 / 中断），携带 reason。
 *
 * <p>受检异常，强制 {@code AbstractSourceAdapter} 模板层 catch 后走降级，不吞。
 */
public class ResilienceException extends Exception {

    private final String reason;

    public ResilienceException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public ResilienceException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
