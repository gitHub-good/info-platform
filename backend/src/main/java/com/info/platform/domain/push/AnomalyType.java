package com.info.platform.domain.push;

/**
 * 异动类型（anomaly_event.anomaly_type 持久化为 TINYINT）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。首期（T13）实现 {@link #PRICE_CHANGE} 涨跌幅； {@link #VOLUME}
 * 量异动 / {@link #EVENT} 事件留枚举占位，后续任务补判定规则。
 *
 * <p>对齐技术方案 §4.2 anomaly_event DDL 注释：1 涨跌幅 / 2 量异动 / 3 事件。
 */
public enum AnomalyType {
    /** 涨跌幅异动（日涨跌幅绝对值 >= 阈值）。 */
    PRICE_CHANGE(1),
    /** 量异动（成交量突增/突缩，预留）。 */
    VOLUME(2),
    /** 事件异动（重大公告/事件入库触发，预留）。 */
    EVENT(3);

    private final int code;

    AnomalyType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static AnomalyType fromCode(int code) {
        for (AnomalyType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知 anomalyType: " + code);
    }
}
