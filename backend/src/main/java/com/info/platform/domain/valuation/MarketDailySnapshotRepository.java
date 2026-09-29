package com.info.platform.domain.valuation;

import com.info.platform.domain.aggregation.Market;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 行情估值日快照仓储端口（{@code market_daily_snapshot}，M20 方案 §4.1/§4.9）：腾讯批量拉取一次落库 （UNIQUE(subject_id,
 * snapshot_date) 当日重跑覆盖）；读路径供 F5 投影与 coverage marketDataRows 计数； v2 时序分位按日窗取数（idx_mds_date）。
 */
public interface MarketDailySnapshotRepository {

    /** 批量 UPSERT 行情快照行。 */
    int upsertAll(List<MarketDailyRow> rows);

    /** 指定快照日全量行（subject_id → 行；F5 横截面与 NO_MARKET_DATA 判定原料）。 */
    Map<Long, MarketDailyRow> findByDate(String snapshotDate);

    /** 指定快照日行数（coverage marketDataRows）。 */
    long countByDate(String snapshotDate);

    /** 交易日序列（distinct snapshot_date 升序，M22 T193 hits-v1）——库内交易日历代理（无价日天然不在序列，窗口推导即跳过）。 */
    List<String> findTradingDates();

    /** 分市场交易日序列（M29 T256，hit-stats market 参数——该市场标的的 distinct snapshot_date，不与他市场混序）。 */
    List<String> findTradingDates(Market market);

    /** 该市场最近行情快照日（M29 T256，港美股榜单 basis「数据口径」留痕——美股盘中未收敛照常出榜，快照日如实回显； 无任何快照返回 empty）。 */
    Optional<String> findLatestSnapshotDate(Market market);

    /** 指定快照日收盘价投影（subject_id → close；无行/close NULL 均缺键——有价样本口径，M22 T193）。 */
    Map<Long, Double> findClosePrices(String snapshotDate);

    /**
     * 行情快照行（估值列 null = 缺数——空或 ≤0 落库前归一）。
     *
     * @param marketCap 总市值（<b>亿原币</b>，M29 T252 港美股行——腾讯 @44/新浪归一；A 股行 null 未回填，V37 ⑦ 口径）
     * @param currency 币种（CNY / HKD / USD；A 股既有行 null）
     */
    record MarketDailyRow(
            long subjectId,
            String snapshotDate,
            Double closePrice,
            Double pctChange,
            Double turnoverRate,
            Double amplitude,
            Double volume,
            Double peTtm,
            Double pb,
            String source,
            String quoteTime,
            Double marketCap,
            String currency) {

        /** 兼容构造（A 股既有调用面：市值/币种未回填 null，M20 T170 行为不变）。 */
        public MarketDailyRow(
                long subjectId,
                String snapshotDate,
                Double closePrice,
                Double pctChange,
                Double turnoverRate,
                Double amplitude,
                Double volume,
                Double peTtm,
                Double pb,
                String source,
                String quoteTime) {
            this(
                    subjectId,
                    snapshotDate,
                    closePrice,
                    pctChange,
                    turnoverRate,
                    amplitude,
                    volume,
                    peTtm,
                    pb,
                    source,
                    quoteTime,
                    null,
                    null);
        }
    }
}
