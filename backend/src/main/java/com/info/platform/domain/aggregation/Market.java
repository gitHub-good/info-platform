package com.info.platform.domain.aggregation;

/**
 * 标的市场（与 subject_master.market 列一致存储为枚举名文本）。
 */
public enum Market {
    A_SHARE,
    HK,
    INDEX,
    SECTOR;

    public static Market fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("market 不能为空");
        }
        return Market.valueOf(name.trim().toUpperCase());
    }
}
