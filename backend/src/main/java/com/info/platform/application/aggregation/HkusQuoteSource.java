package com.info.platform.application.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 港美股行情批量取数端口（M29 T252，ADR-0064 裁决 1「腾讯主 + 新浪备双通道轮级互切」）：应用层编排（{@code
 * HKUSMarketSnapshotService}）依赖端口、基础设施层以腾讯/新浪两实现适配——备选源对快照层透明（ADR-0031 中间结构复用）。
 *
 * <p>返回行 = 东财 f 键中间结构（f43 现价 / f170 涨跌幅 / f47 量 / f48 额 / f162 PE / f30 源时间戳）+ 原生位 {@code
 * market_cap}（亿原币）/ {@code currency}（HKD / USD）。{@code sourceCode} 落 {@code
 * market_daily_snapshot.source} 留痕（降级轮可区分兜底源）。
 */
public interface HkusQuoteSource {

    /** 源标注（{@code market_daily_snapshot.source} 落行值：tencent / sina）。 */
    String sourceCode();

    /**
     * 按内部标的码批量取数（实现自控符号换算与分块；响应行缺席即无键，不视为错误）。
     *
     * @param subjectCodes 内部标的码（如 {@code HK00700} / {@code USAAPL}）
     * @return 键 = 标的码 → f 键中间结构
     */
    Map<String, Map<String, Object>> fetchBySubjectCodes(List<String> subjectCodes);
}
