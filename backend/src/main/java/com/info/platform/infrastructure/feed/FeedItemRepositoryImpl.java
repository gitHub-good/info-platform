package com.info.platform.infrastructure.feed;

import com.info.platform.domain.analysis.L0Result;
import com.info.platform.domain.feed.FeedItem;
import com.info.platform.domain.feed.FeedItemRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link FeedItemRepository} 端口的 SQLite 实现（M13 T104，ADR-0039；T160 增资讯库读模型）。
 *
 * <p>批量落库走 {@link JdbcTemplate} {@code INSERT OR IGNORE}（MyBatis-Plus 无该语义，SubjectRepository 同惯例）——
 * idx_news_fp（全局指纹）与 idx_news_src_ext（源内 external_id，NULL 豁免）双索引同时兜底， 调度重入/补抓重拉/并发同稿均收敛，实插行数即返回值
 * （「应插 − 实插 = dup」对账口径）。 读路径默认 join info_source 排除软删源（历史行保留、默认流不可见）。 id DESC newest-first：id
 * 自增顺序即入库顺序，游标分页无需 OFFSET（LIMIT n）。
 *
 * <p><b>资讯库读模型（T160，REQ-20260926-16 拍板一）</b>：news_item 主锚 LEFT JOIN news_analysis（UNIQUE(news_id)
 * 走索引）+ 近重复主条二次 LEFT JOIN news_item 取 url——单查询直查（页大小 ≤50，量级毫秒级）。索引评估留档：① q 关键词 {@code LIKE '%…%'}
 * 前缀通配无法走索引（10 万级全扫毫秒可接受，抽查场景非高频主路径——REQ 非功能「性能」节裁量）； ② l0/l1 过滤不另建索引：分页恒由 ni.id DESC + LIMIT 驱动（id
 * 自增即入库序），analysis 列经 join 后逐行判定， news_analysis 既有 UNIQUE(news_id)/idx_na_main 不需要新索引；③ 无 analysis
 * 行按 PASS 兜底（l0=PASS 条件为 {@code na.news_id IS NULL OR na.l0_result='PASS'}，行映射同口径）。
 */
@Repository
public class FeedItemRepositoryImpl implements FeedItemRepository {

    private static final String INSERT_IGNORE_SQL =
            """
            INSERT OR IGNORE INTO news_item
              (source_id, external_id, title, summary, url, author, published_at, fetched_at,
               fingerprint, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 资讯库读模型基座：软删源 join + analysis LEFT JOIN（1:1）+ 近重复主条 url 直查。 */
    private static final String LIBRARY_SELECT_SQL =
            """
            SELECT ni.id, ni.source_id, ni.external_id, ni.title, ni.summary, ni.url, ni.author,
                   ni.published_at, ni.fetched_at, ni.fingerprint, ni.status,
                   ni.created_at, ni.updated_at,
                   na.l0_result, na.l0_detail, na.main_category, na.confidence, na.low_confidence,
                   na.near_dup_of AS near_dup_master_id, master.url AS near_dup_master_url
              FROM news_item ni
              JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
              LEFT JOIN news_analysis na ON na.news_id = ni.id
              LEFT JOIN news_item master ON master.id = na.near_dup_of
             WHERE 1=1
            """;

    private static final RowMapper<LibraryRow> LIBRARY_ROW = FeedItemRepositoryImpl::toLibraryRow;

    private final JdbcTemplate jdbcTemplate;

    public FeedItemRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertIgnoreBatch(List<FeedItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int[] results =
                jdbcTemplate.batchUpdate(
                        INSERT_IGNORE_SQL,
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(java.sql.PreparedStatement ps, int i)
                                    throws SQLException {
                                FeedItem item = items.get(i);
                                ps.setLong(1, item.sourceId());
                                ps.setString(2, item.externalId());
                                ps.setString(3, item.title());
                                ps.setString(4, item.summary());
                                ps.setString(5, item.url());
                                ps.setString(6, item.author());
                                ps.setString(7, item.publishedAt().toString());
                                ps.setString(8, item.fetchedAt().toString());
                                ps.setString(9, item.fingerprint());
                                ps.setInt(10, item.status());
                                ps.setString(11, now.toString());
                                ps.setString(12, now.toString());
                            }

                            @Override
                            public int getBatchSize() {
                                return items.size();
                            }
                        });
        int inserted = 0;
        for (int result : results) {
            // SQLite batch 返回每语句变更行数（IGNORE 为 0）；Statement.SUCCESS_NO_INFO 防御性按 1 计
            inserted += result == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, result);
        }
        return inserted;
    }

    @Override
    public List<LibraryRow> findLatest(Long sourceId, Long beforeId, int limit) {
        Query query = libraryQuery();
        applySourceFilter(query, sourceId);
        if (beforeId != null && beforeId > 0) {
            query.append(" AND ni.id < ?", beforeId);
        }
        query.appendRaw(" ORDER BY ni.id DESC LIMIT " + limit);
        return jdbcTemplate.query(query.sql(), LIBRARY_ROW, query.args());
    }

    @Override
    public List<LibraryRow> findPage(LibraryFilter filter, int page, int size) {
        Query query = libraryQuery();
        applyLibraryFilter(query, filter);
        // LIMIT/OFFSET 从简（ADR-0035：页码上限下实测毫秒级）
        query.appendRaw(" ORDER BY ni.id DESC LIMIT " + size + " OFFSET " + (page - 1) * size);
        return jdbcTemplate.query(query.sql(), LIBRARY_ROW, query.args());
    }

    @Override
    public long countByFilter(LibraryFilter filter) {
        Query query =
                new Query(
                        """
                        SELECT COUNT(*)
                          FROM news_item ni
                          JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
                          LEFT JOIN news_analysis na ON na.news_id = ni.id
                         WHERE 1=1
                        """);
        applyLibraryFilter(query, filter);
        Long count = jdbcTemplate.queryForObject(query.sql(), Long.class, query.args());
        return count == null ? 0L : count;
    }

    @Override
    public List<Long> fetchLatencyMillisSince(String sinceISO) {
        // 感知延迟样本（§4.8）：fetched_at − published_at，负值截 0（源侧时钟超前不产生负口径）
        return jdbcTemplate.query(
                "SELECT fetched_at, published_at FROM news_item WHERE created_at >= ?",
                (rs, rowNum) -> {
                    long millis =
                            Duration.between(
                                            Instant.parse(rs.getString("published_at")),
                                            Instant.parse(rs.getString("fetched_at")))
                                    .toMillis();
                    return Math.max(0L, millis);
                },
                sinceISO);
    }

    @Override
    public List<FeedItemRepository.LatencySample> fetchLatencySamplesSince(String sinceISO) {
        // T114 大盘样本（带源维度与入库时刻）：应用层按「排除每源首日 + 排除日粒度源」过滤（ADR-0045）
        return jdbcTemplate.query(
                "SELECT source_id, created_at, fetched_at, published_at FROM news_item"
                        + " WHERE created_at >= ?",
                (rs, rowNum) ->
                        new FeedItemRepository.LatencySample(
                                rs.getLong("source_id"),
                                Instant.parse(rs.getString("created_at")),
                                Math.max(
                                        0L,
                                        Duration.between(
                                                        Instant.parse(rs.getString("published_at")),
                                                        Instant.parse(rs.getString("fetched_at")))
                                                .toMillis())),
                sinceISO);
    }

    @Override
    public Map<Long, Instant> findFirstIngestAt() {
        return jdbcTemplate.query(
                "SELECT source_id, MIN(created_at) AS first_at FROM news_item GROUP BY source_id",
                (org.springframework.jdbc.core.ResultSetExtractor<Map<Long, Instant>>)
                        rs -> {
                            Map<Long, Instant> firstAt = new java.util.HashMap<>();
                            while (rs.next()) {
                                firstAt.put(
                                        rs.getLong("source_id"),
                                        Instant.parse(rs.getString("first_at")));
                            }
                            return firstAt;
                        });
    }

    @Override
    public Map<Long, Long> countGroupedBySource() {
        return jdbcTemplate.query(
                "SELECT source_id, COUNT(*) AS total FROM news_item GROUP BY source_id",
                (org.springframework.jdbc.core.ResultSetExtractor<Map<Long, Long>>)
                        rs -> {
                            Map<Long, Long> totals = new java.util.HashMap<>();
                            while (rs.next()) {
                                totals.put(rs.getLong("source_id"), rs.getLong("total"));
                            }
                            return totals;
                        });
    }

    /** 资讯库读模型查询基座（软删源 join + analysis/主条 LEFT JOIN + WHERE 1=1 起步，过滤片段顺序追加）。 */
    private static Query libraryQuery() {
        return new Query(LIBRARY_SELECT_SQL);
    }

    private static void applySourceFilter(Query query, Long sourceId) {
        if (sourceId != null) {
            query.append(" AND ni.source_id = ?", sourceId);
        }
    }

    /**
     * 资讯库组合 WHERE 一处组装（sourceId + q LIKE + l0 状态 + l1 主分类，全 AND），{@link #findPage}/ {@link
     * #countByFilter} 两用——页数据与计数同口径（M9 PolicyRepository 同惯例）。
     */
    private static void applyLibraryFilter(Query query, LibraryFilter filter) {
        applySourceFilter(query, filter.sourceId());
        if (filter.keyword() != null) {
            // title 或 summary 任一命中（OR）；NULL LIKE 天然不命中（正确语义）
            String pattern = likePattern(filter.keyword());
            query.append(" AND (ni.title LIKE ? ESCAPE '\\'", pattern);
            query.append(" OR ni.summary LIKE ? ESCAPE '\\')", pattern);
        }
        if (filter.l0() != null) {
            if (filter.l0() == L0Result.PASS) {
                // 无 analysis 行（保留期清理错位滞留条目）按 PASS 兜底展示（REQ 拍板一 join 语义注记）
                query.appendRaw(" AND (na.news_id IS NULL OR na.l0_result = 'PASS')");
            } else {
                query.append(" AND na.l0_result = ?", filter.l0().name());
            }
        }
        if (filter.mainCategory() != null) {
            // main_category 仅 L1 DONE 有值：PENDING/FAILED/无 analysis 行自然不含（REQ 故事 2 场景 3）
            query.append(" AND na.main_category = ?", filter.mainCategory());
        }
        // 发布时间窗（BUG-M23-01 补齐）：ISO yyyy-MM-dd，published_at 为 ISO UTC 文本，字典序可比。
        // 上海日界换算：from → UTC 前一日 16:00 起、to → UTC 当日 16:00 止（含端点）。
        if (filter.publishedFrom() != null) {
            query.append(" AND ni.published_at >= ?", filter.publishedFrom() + "T16:00:00");
        }
        if (filter.publishedTo() != null) {
            query.append(" AND ni.published_at <= ?", filter.publishedTo() + "T16:00:59");
        }
        // 入库时间窗（T210，M24 V2.4，REQ-20260928-20 拍板三对账锚）：fetched_at 为 ISO UTC 文本，字典序可比；
        // 上海日 D = UTC [D-1 16:00:00, D 16:00:00)——与 source_daily_stats.stat_date 同口径
        // （FeedIngestService 落库 fetched_at 与计数 stat_date 出自同一轮 now），「今日入库」弹框 total 与
        // 大盘 newCount 构造上可对账相等。
        if (filter.fetchedFrom() != null) {
            query.append(" AND ni.fetched_at >= ?", shDayStartUtc(filter.fetchedFrom()));
        }
        if (filter.fetchedTo() != null) {
            query.append(" AND ni.fetched_at < ?", shDayEndExclusiveUtc(filter.fetchedTo()));
        }
    }

    /** 上海日 D 的 UTC 起点文本（D-1T16:00:00Z；ISO UTC 文本字典序可比，日窗含起点）。 */
    static String shDayStartUtc(String day) {
        return java.time.LocalDate.parse(day).minusDays(1) + "T16:00:00";
    }

    /** 上海日 D 的 UTC 开区间上界文本（DT16:00:00Z = 次日 00:00 上海；严格小于，无 :59 溢出秒）。 */
    static String shDayEndExclusiveUtc(String day) {
        return java.time.LocalDate.parse(day) + "T16:00:00";
    }

    /**
     * LIKE 模式串：转义 {@code \ % _} 后包 % 通配（防用户输入通配符误当语义，M9/ADR-0035 先例—— PolicyRepository 同款）。
     *
     * <p>替换顺序必须先 {@code \}（否则后续引入的转义符会被二次转义）。
     */
    static String likePattern(String raw) {
        return "%" + raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 参数化查询组装小件（SQL 片段 + 顺序参数；appendRaw 仅用于本类受控 int 字面量）。 */
    private static final class Query {
        private final StringBuilder sql;
        private final List<Object> args = new java.util.ArrayList<>();

        Query(String baseSql) {
            this.sql = new StringBuilder(baseSql);
        }

        void append(String fragment, Object arg) {
            sql.append(fragment);
            args.add(arg);
        }

        void appendRaw(String fragment) {
            sql.append(fragment);
        }

        String sql() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }
    }

    private static FeedItem toItem(ResultSet rs, int rowNum) throws SQLException {
        return new FeedItem(
                rs.getLong("id"),
                rs.getLong("source_id"),
                nullable(rs.getString("external_id")),
                rs.getString("title"),
                nullable(rs.getString("summary")),
                nullable(rs.getString("url")),
                nullable(rs.getString("author")),
                Instant.parse(rs.getString("published_at")),
                Instant.parse(rs.getString("fetched_at")),
                rs.getString("fingerprint"),
                rs.getInt("status"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    /** 资讯库行映射：无 analysis 行（三列全 NULL）兜底 l0=PASS、分类 null（「未分类」由前端呈现）。 */
    private static LibraryRow toLibraryRow(ResultSet rs, int rowNum) throws SQLException {
        String l0Name = nullable(rs.getString("l0_result"));
        Long masterId =
                rs.getObject("near_dup_master_id") == null
                        ? null
                        : rs.getLong("near_dup_master_id");
        return new LibraryRow(
                toItem(rs, rowNum),
                l0Name == null ? L0Result.PASS : L0Result.fromName(l0Name),
                nullable(rs.getString("l0_detail")),
                nullable(rs.getString("main_category")),
                rs.getObject("confidence") == null ? null : rs.getDouble("confidence"),
                rs.getObject("low_confidence") != null && rs.getInt("low_confidence") == 1,
                masterId,
                nullable(rs.getString("near_dup_master_url")));
    }

    private static String nullable(String value) {
        return value == null ? null : value;
    }
}
