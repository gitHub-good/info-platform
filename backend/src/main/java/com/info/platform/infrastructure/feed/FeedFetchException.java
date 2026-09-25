package com.info.platform.infrastructure.feed;

/**
 * 取数失败异常（M13 T101，方案 §4.3）：HTTP 失败/结构不符/解析失败统一抛出，消息携带源定位（sourceCode/endpoint）； 由摄取服务捕获走指数退避。
 */
public class FeedFetchException extends RuntimeException {

    public FeedFetchException(String message) {
        super(message);
    }

    public FeedFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
