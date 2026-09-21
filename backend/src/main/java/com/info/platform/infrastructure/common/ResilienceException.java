package com.info.platform.infrastructure.common;

/**
 * 弹性调用失败（超时 / 重试耗尽 / 中断），携带 reason 与 {@link FailureKind}。
 *
 * <p>受检异常，强制 {@code AbstractSourceAdapter} 模板层 catch 后走降级，不吞。 {@link FailureKind} 由 {@code
 * ResilienceRunner} 在抛出时按最后一次失败原因填充，供 T16 数据源事件记录区分 {@code TIMEOUT}（记
 * data_source_event.event_type=2 超时）与 {@code ERROR}/{@code INTERRUPTED}（记 event_type=3 错误），对齐技术方案
 * §4.3 流程 1 + §5 可观测。
 */
public class ResilienceException extends Exception {

    /** 弹性失败种类：超时 / 异常（doFetch 抛出）/ 中断。 */
    public enum FailureKind {
        /** Future.get 超时、重试耗尽。 */
        TIMEOUT,
        /** doFetch 抛异常（ExecutionException）、重试耗尽。 */
        ERROR,
        /** 执行线程被中断。 */
        INTERRUPTED
    }

    private final String reason;
    private final FailureKind kind;

    public ResilienceException(String reason) {
        this(reason, null, FailureKind.ERROR);
    }

    public ResilienceException(String reason, Throwable cause) {
        this(reason, cause, FailureKind.ERROR);
    }

    public ResilienceException(String reason, Throwable cause, FailureKind kind) {
        super(reason, cause);
        this.reason = reason;
        this.kind = kind == null ? FailureKind.ERROR : kind;
    }

    public String getReason() {
        return reason;
    }

    public FailureKind getKind() {
        return kind;
    }
}
