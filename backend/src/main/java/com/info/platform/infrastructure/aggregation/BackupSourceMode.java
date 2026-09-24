package com.info.platform.infrastructure.aggregation;

import java.util.Locale;

/**
 * 行情/估值源选择模式（ADR-0031，对齐 ADR-0030 {@code subject.sync.a-share-source} 先例）。
 *
 * <p>{@code adapter.quote-source} / {@code adapter.valuation-source} 共用：
 *
 * <ul>
 *   <li><b>auto</b>（默认）：东财失败（HTTP 错误/异常）或空响应（push2 IP 封禁签名）→ WARN → 自动降级腾讯备选源重拉；
 *       东财恢复后每轮仍先走东财，无需人工切回
 *   <li><b>eastmoney / tencent</b>：强制单源（排障用——验证降级逻辑是否为故障源），失败即按既有弹性语义降级
 * </ul>
 *
 * <p>配置笔误启动即失败（fail-fast）；空值容错回落 auto（语义同 {@code @Value} 缺省）。
 */
enum BackupSourceMode {
    AUTO,
    EASTMONEY,
    TENCENT;

    /** 解析配置值：大小写不敏感；空值回落 auto；非法取值抛 {@link IllegalArgumentException}（fail-fast）。 */
    static BackupSourceMode parse(String raw, String configKey) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "eastmoney" -> EASTMONEY;
            case "tencent" -> TENCENT;
            default -> throw new IllegalArgumentException(
                    configKey + " 非法取值: '" + raw + "'（合法: auto | eastmoney | tencent）");
        };
    }
}
