package com.info.platform.domain.subscription;

/**
 * 自选清单启用状态：1 启用 / 0 删除（软删除预留）。
 *
 * <p>领域层纯净枚举（仅 JDK），与持久化层 {@code TINYINT} 互转。T11 现仅创建启用清单， 软删除清单项走物理 DELETE 以释放 {@code
 * UNIQUE(watchlist_id, subject_id)} 便于重新加标的。
 */
public enum WatchlistStatus {
    DELETED(0),
    ENABLED(1);

    private final int code;

    WatchlistStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static WatchlistStatus fromCode(int code) {
        for (WatchlistStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知 watchlistStatus: " + code);
    }
}
