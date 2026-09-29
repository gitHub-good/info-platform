package com.info.platform.domain.aggregation;

/** 标的市场（与 subject_master.market 列一致存储为枚举名文本）。 */
public enum Market {
    A_SHARE,
    HK,
    /** 美股（M29 T251 建池：F10 `.N`/`.O` 主板后缀预筛入池，ADR-0064 裁决 3）。 */
    US,
    INDEX,
    SECTOR;

    public static Market fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("market 不能为空");
        }
        return Market.valueOf(name.trim().toUpperCase());
    }
}
