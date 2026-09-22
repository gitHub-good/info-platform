package com.info.platform.domain.aggregation;

/**
 * 数据源事件类型（data_source_event.event_type 持久化为 TINYINT）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。对齐技术方案 §4.2 data_source_event DDL 注释： 1 缺失 / 2 超时 / 3
 * 错误 / 4 限频；§4.3 流程 1 各 adapter 失败/缺失触发记录；§5 非功能「可观测：数据源缺失记 WARN」。
 *
 * <p>触发点（由 {@code AbstractSourceAdapter} 模板降级路径旁路记录，T16 落地）：
 *
 * <ul>
 *   <li>{@link #MISSING} —— {@code doFetch} 返回 {@code Optional.empty()}（源当日无数据，成功调用非异常）
 *   <li>{@link #TIMEOUT} —— {@code ResilienceRunner} 超时、重试耗尽（{@code FailureKind.TIMEOUT}）
 *   <li>{@link #ERROR} —— {@code doFetch} 抛异常 / 被中断（{@code FailureKind.ERROR / INTERRUPTED}）
 *   <li>{@link #LIMITED} —— 熔断器 {@code allowRequest=false}（circuit-open，快速失败不调 doFetch）
 * </ul>
 */
public enum DataSourceEventType {
    /** 数据源当日无数据（doFetch 返回 empty，成功调用非异常，不阻断）。 */
    MISSING(1),
    /** 取数超时、重试耗尽（ResilienceRunner Future.get 超时）。 */
    TIMEOUT(2),
    /** 取数异常 / 中断（doFetch 抛异常或线程被中断）。 */
    ERROR(3),
    /** 熔断限频（circuit-open，快速失败不调 doFetch）。 */
    LIMITED(4),
    /** 取数成功心跳（T36 / 方案 §4.3：成功路径旁路记录，60s/源内存节流；健康徽章与概览「最近一次抓取结果」的数据来源）。 */
    OK(5);

    private final int code;

    DataSourceEventType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DataSourceEventType fromCode(int code) {
        for (DataSourceEventType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 dataSourceEventType: " + code);
    }
}
