package com.info.platform.domain.newspulse;

import java.time.Duration;

/**
 * 资讯脉搏时间窗（V3.2 M28）：30m / 1h / 3h / 6h / 12h / 24h 六档。
 *
 * <p>领域层纯净枚举（仅 JDK）。{@link #code()} 为 API/表 {@code window_key} 口径；{@link #duration()} 为窗口时长， Job
 * 侧按「距上次分析 ≥ 窗口时长 × 0.9」判断过期（30min 档每 tick 必刷、24h 档每日一刷，自然错峰）。
 */
public enum NewsPulseWindow {
    W30M("30m", Duration.ofMinutes(30), "30 分钟"),
    W1H("1h", Duration.ofHours(1), "1 小时"),
    W3H("3h", Duration.ofHours(3), "3 小时"),
    W6H("6h", Duration.ofHours(6), "6 小时"),
    W12H("12h", Duration.ofHours(12), "12 小时"),
    W24H("24h", Duration.ofHours(24), "24 小时");

    private final String code;
    private final Duration duration;
    private final String label;

    NewsPulseWindow(String code, Duration duration, String label) {
        this.code = code;
        this.duration = duration;
        this.label = label;
    }

    /** 表/API 口径码。 */
    public String code() {
        return code;
    }

    /** 窗口时长。 */
    public Duration duration() {
        return duration;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }

    /** code → 枚举（API 参数解析；非法值由调用方落 2xxx）。 */
    public static NewsPulseWindow ofCode(String code) {
        for (NewsPulseWindow window : values()) {
            if (window.code.equals(code)) {
                return window;
            }
        }
        throw new IllegalArgumentException("未知时间窗: " + code);
    }
}
