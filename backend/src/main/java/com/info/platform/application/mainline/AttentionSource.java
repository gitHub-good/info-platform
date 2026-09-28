package com.info.platform.application.mainline;

/**
 * 主力关注度代理源端口（M27 T244，方案 §4.4.4——IndustryQuoteSource 同款依赖倒置）：datacenter 龙虎榜 30 日计数 + 增减持净方向。
 *
 * <p>实现（infrastructure/aggregation {@code EastmoneyAttentionClient}）：Spike-D D-4/D-5 实测参数形态；调用点在主线
 * Job 对 Top3 龙头逐只（≤15 只 × 2 报表 = ≤30 请求/日集中盘后，页间 500ms 礼貌间隔）。
 */
public interface AttentionSource {

    /**
     * 龙虎榜近窗计数 + 最近一次上榜。
     *
     * @param code 6 位证券代码
     * @param sinceDate TRADE_DATE 下界（yyyy-MM-dd，含）
     */
    LhbSummary fetchLhbSummary(String code, String sinceDate);

    /**
     * 增减持近窗净方向。
     *
     * @param sinceDate NOTICE_DATE 下界（yyyy-MM-dd，含）
     */
    HolderChangeSummary fetchHolderChangeSummary(String code, String sinceDate);

    /** 龙虎榜摘要（30 日计数 + 最近上榜日与原因）。 */
    record LhbSummary(int count, String latestDate, String reason) {}

    /** 增减持摘要（增/减计数 + 净方向：净增持/净减持/均衡）。 */
    record HolderChangeSummary(int increaseCount, int decreaseCount, String netDirection) {}
}
