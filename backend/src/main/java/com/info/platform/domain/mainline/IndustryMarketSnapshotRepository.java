package com.info.platform.domain.mainline;

import java.util.List;
import java.util.Optional;

/**
 * 行业行情快照仓储端口（{@code industry_market_snapshot}，M27 T242，V35 表①）。领域层纯净接口：写路径为当日板块行 + 行业行同事务
 * UPSERT（UNIQUE(row_type, dim_name, snapshot_date)——盘中轮幂等覆盖，跨日自然新增）；读路径供热力图 / 下钻 / 主线计算（当日 + 近 5
 * 交易日窗）。
 */
public interface IndustryMarketSnapshotRepository {

    /**
     * 批量 UPSERT 快照行（板块行 + 行业行同一事务；SQLite 单写者毫秒级）。
     *
     * @return 累计受影响行数
     */
    int upsertAll(List<MarketSnapshotRow> rows);

    /** 指定快照日 INDUSTRY 行（申万行业名升序确定性；无行返回空——热力图/主线价格维读面）。 */
    List<MarketSnapshotRow> findIndustryRows(String snapshotDate);

    /** 指定快照日全部 BOARD 行（板块名升序；下钻通道 A 形态读面）。 */
    List<MarketSnapshotRow> findBoardRows(String snapshotDate);

    /** 指定快照日某行业的 BOARD 行（下钻端点按行业取板块明细）。 */
    List<MarketSnapshotRow> findBoardRowsOfIndustry(String snapshotDate, String industry);

    /** 最新有行快照日（yyyy-MM-dd；从未采集返回 empty——30093 语义）。 */
    Optional<String> latestSnapshotDate();

    /** 近 N 个 distinct 快照日（降序；交易日集 = 本表 distinct snapshot_date，pct_d5 自算与持续性窗口径）。 */
    List<String> recentSnapshotDates(int limit);

    /** 指定日期集内 INDUSTRY 行的 (snapshot_date, industry, pct_day) 投影（pct_d5 复利自算与价格侧持续性原料）。 */
    List<HistoryPctDay> findIndustryPctDayForDates(List<String> snapshotDates);

    /**
     * 快照行（两 row_type 共用读写形态；leaderStock 为 JSON 文本透传——领域不依赖 JSON 库）。
     *
     * @param rowType BOARD / INDUSTRY
     * @param dimName 板块名（BOARD）/ 申万行业名（INDUSTRY）
     * @param industry 申万 31 枚举
     * @param snapshotDate Asia/Shanghai yyyy-MM-dd
     */
    record MarketSnapshotRow(
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
            String quoteTime) {}

    /** 历史日 pct_day 投影行（pct_d5 复利与持续性判定原料）。 */
    record HistoryPctDay(String snapshotDate, String industry, Double pctDay) {}
}
