package com.info.platform.domain.analysis;

import java.time.Duration;
import java.util.Locale;

/**
 * 行业热度快照窗口（M15 T123，方案 §4.5）：H24 当日 24 小时窗 / D7 七天窗——半衰期与窗口长度双轨（12h / 48h）。
 *
 * <p>线格式 {@code H24|D7}（端点 window 参数）；prev 窗口 = 等长对齐错位（[end−2L, end−L)，环比口径）。
 */
public enum HeatWindow {
    H24(Duration.ofHours(24)),
    D7(Duration.ofDays(7));

    private final Duration length;

    HeatWindow(Duration length) {
        this.length = length;
    }

    /** 窗口长度。 */
    public Duration length() {
        return length;
    }

    /** 线格式解析（大小写不敏感；null/空白/未知值返回 null——由接口层给 30076 字段级提示）。 */
    public static HeatWindow fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (HeatWindow window : values()) {
            if (window.name().equals(normalized)) {
                return window;
            }
        }
        return null;
    }
}
