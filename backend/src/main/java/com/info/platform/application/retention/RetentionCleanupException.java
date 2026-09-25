package com.info.platform.application.retention;

/**
 * 留痕清理轮失败（T71，方案 §4.5）：轮末汇总抛出——单表失败已续跑其余表，异常消息同时携带<b>成功表明细</b>（含失败表的
 * 已删批部分计数）与<b>失败表原因</b>，由执行通道记 FAILED + error_message；下一轮按调度继续。
 */
public class RetentionCleanupException extends RuntimeException {

    public RetentionCleanupException(String message) {
        super(message);
    }

    public RetentionCleanupException(String message, Throwable cause) {
        super(message, cause);
    }
}
