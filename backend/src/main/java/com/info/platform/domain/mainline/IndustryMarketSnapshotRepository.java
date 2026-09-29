package com.info.platform.domain.mainline;

import com.info.platform.domain.aggregation.Market;
import java.util.List;
import java.util.Optional;

/**
 * 行业行情快照仓储端口（{@code industry_market_snapshot}，M27 T242，V35 表①；V37 起唯一键含 market 维）。领域层纯净接口：写路径为当日板块行
 * + 行业行同事务 UPSERT（UNIQUE(row_type, dim_name, snapshot_date, market)——盘中轮幂等覆盖，跨日自然新增；港美股聚合行由 T252
 * HKUS 快照轮写入）； 读路径供热力图 / 下钻 / 主线计算（M29 T255 分市场参数化——无参重载恒 A_SHARE 保护性过滤，既有 M27 调用面零回归）。
 */
public interface IndustryMarketSnapshotRepository {

    /**
     * 批量 UPSERT 快照行（板块行 + 行业行同一事务；SQLite 单写者毫秒级）。
     *
     * @return 累计受影响行数
     */
    int upsertAll(List<MarketSnapshotRow> rows);

    /**
     * 批量 UPSERT 单市场行业行 + 同事务清理该 market 当日「本次未命中的旧行」（M29 P2-01 修复：{@code subject.industry} 被 F10
     * 回填改写后，旧行业聚合行不再残留——UPSERT 只增不清的孤儿行根治）。空清单 no-op 不清理（聚合空 = 数据异常轮，沿「双链全败不动旧快照」保守语义）。
     *
     * @param market HK / US（港美股聚合行写侧；A 股通道沿 {@link #upsertAll} 不变）
     * @param snapshotDate Asia/Shanghai yyyy-MM-dd（当日口径，跨日行不动）
     * @param rows 本次聚合产出的该市场 INDUSTRY 行（dim_name 集合 = 保留集）
     * @return 本次 UPSERT 受影响行数（清理行数另经日志留痕，不计入）
     */
    int replaceIndustryRows(String market, String snapshotDate, List<MarketSnapshotRow> rows);

    /**
     * 指定快照日 INDUSTRY 行（A_SHARE 保护性过滤——M29 T252；分市场读走 {@link #findIndustryRows(String, Market)}）。
     */
    List<MarketSnapshotRow> findIndustryRows(String snapshotDate);

    /** 指定快照日 + 市场 INDUSTRY 行（行业名升序确定性；无行返回空——热力图/主线价格维分市场读面）。 */
    List<MarketSnapshotRow> findIndustryRows(String snapshotDate, Market market);

    /** 指定快照日全部 BOARD 行（板块名升序；下钻通道 A 形态读面——A 股通道专有）。 */
    List<MarketSnapshotRow> findBoardRows(String snapshotDate);

    /** 指定快照日某行业的 BOARD 行（下钻端点按行业取板块明细；A 股通道专有）。 */
    List<MarketSnapshotRow> findBoardRowsOfIndustry(String snapshotDate, String industry);

    /** 最新有行快照日（A_SHARE 口径；分市场读走 {@link #latestSnapshotDate(Market)}）。 */
    Optional<String> latestSnapshotDate();

    /** 指定市场最新有行快照日（yyyy-MM-dd；该市场从未采集返回 empty）。 */
    Optional<String> latestSnapshotDate(Market market);

    /** 近 N 个 distinct 快照日（A_SHARE 口径；分市场读走 {@link #recentSnapshotDates(int, Market)}）。 */
    List<String> recentSnapshotDates(int limit);

    /** 指定市场近 N 个 distinct 快照日（降序；交易日集 = 本表 distinct snapshot_date，持续性窗口径）。 */
    List<String> recentSnapshotDates(int limit, Market market);

    /** 指定日期集内 INDUSTRY 行的 (snapshot_date, industry, pct_day) 投影（A_SHARE 口径；分市场读走下方重载）。 */
    List<HistoryPctDay> findIndustryPctDayForDates(List<String> snapshotDates);

    /** 指定日期集 + 市场内 INDUSTRY 行投影（pct_d5 复利自算与价格侧持续性原料——港美股 v1 留 NULL 由计算器缺维中性化承接）。 */
    List<HistoryPctDay> findIndustryPctDayForDates(List<String> snapshotDates, Market market);

    /**
     * 快照行（两 row_type 共用读写形态；leaderStock 为 JSON 文本透传——领域不依赖 JSON 库）。
     *
     * @param market A_SHARE / HK / US（V37 起唯一键组成；M27 A 股通道恒 'A_SHARE'，港美股聚合行由 T252 HKUS 快照轮写入）
     * @param rowType BOARD / INDUSTRY
     * @param dimName 板块名（BOARD）/ 行业枚举名（INDUSTRY）
     * @param industry 行业枚举（A 股申万 31 / 港美股各自枚举）
     * @param snapshotDate Asia/Shanghai yyyy-MM-dd
     * @param totalMv Σ 个股市值（<b>元原币</b>；币种见 {@code currency}，拍板六原币不折算）
     * @param currency CNY / HKD / USD
     */
    record MarketSnapshotRow(
            String market,
            String rowType,
            String dimName,
            String industry,
            String snapshotDate,
            Double pctDay,
            Double pctD5,
            Integer upCount,
            Integer downCount,
            Double mainNetFlow,
            Double totalMv,
            String currency,
            String leaderStockJson,
            String source,
            String aggMethod,
            String quoteTime) {

        /** 兼容构造（M27 A 股通道既有调用面：market/currency 恒 'A_SHARE'/'CNY'，行为不变）。 */
        public MarketSnapshotRow(
                String rowType,
                String dimName,
                String industry,
                String snapshotDate,
                Double pctDay,
                Double pctD5,
                Integer upCount,
                Integer downCount,
                Double mainNetFlow,
                Double totalMv,
                String leaderStockJson,
                String source,
                String aggMethod,
                String quoteTime) {
            this(
                    "A_SHARE",
                    rowType,
                    dimName,
                    industry,
                    snapshotDate,
                    pctDay,
                    pctD5,
                    upCount,
                    downCount,
                    mainNetFlow,
                    totalMv,
                    "CNY",
                    leaderStockJson,
                    source,
                    aggMethod,
                    quoteTime);
        }
    }

    /** 历史日 pct_day 投影行（pct_d5 复利与持续性判定原料）。 */
    record HistoryPctDay(String snapshotDate, String industry, Double pctDay) {}
}
