package com.info.platform.domain.aggregation;

import java.util.Locale;

/**
 * 数据源 provider 标识（ADR-0033 降级链模型的枚举维护）：每个数据源可接入一个或多个外部提供方， 降级链 {@code fallbackChain} 的成员即本枚举。
 *
 * <p>领域层纯净枚举，不依赖任何框架类型（对齐 {@link SourceCode} 风格）。对外契约（API JSON / runtime_config 存储 / 日志留痕）统一使用
 * {@link #code()} 小写字符串，内部逻辑一律持枚举比较——新增 provider 必须先在此登记 并同步 {@link SourceProviders}
 * 注册表，链校验器据此拒绝未登记成员。
 */
public enum SourceProvider {
    /** 东方财富（行情/估值/财务/公告/A股列表主源） */
    EASTMONEY("eastmoney", "东方财富"),
    /** 腾讯（行情/估值备选，ADR-0031） */
    TENCENT("tencent", "腾讯"),
    /** 新浪（新闻主源、A股列表备选，ADR-0030/0031） */
    SINA("sina", "新浪"),
    /** 政府网（gov.cn 政策源） */
    GOV("gov", "政府网"),
    /** 本地（事件源读本地 anomaly_event 表，ADR-0013） */
    LOCAL("local", "本地");

    private final String code;
    private final String displayName;

    SourceProvider(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 对外契约标识（API JSON / runtime_config / 日志，小写）。 */
    public String code() {
        return code;
    }

    /** 展示名（{@code RawFetch.source} 标注与前端同名映射共用）。 */
    public String displayName() {
        return displayName;
    }

    /** 按契约 code 解析（大小写不敏感）；未知 code 返回 null（调用方按非法值兜底处理）。 */
    public static SourceProvider fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (SourceProvider provider : values()) {
            if (provider.code.equals(normalized)) {
                return provider;
            }
        }
        return null;
    }
}
