package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.PolicyScopeRepository;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeFilter;
import com.info.platform.domain.feed.PolicyScopeRepository.PolicyScopeRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 政策类口径查询实现（V2.3-M23 T201，方案 §3.1 单 SQL OR 形态，ADR-0062 裁决一）。
 *
 * <p>单 SQL OR（否决两段 UNION：news_item 万行级内无实测收益、count+分页双查询 SQL 面翻倍；&gt;10 万行再切 UNION 两段——预留本注释）。谓词锚定
 * {@code idx_news_published}/{@code idx_na_main} 在册索引，毫秒级； 零新表零新索引（REQ
 * 不可让步项「查询口径非物化新表」）。findPage/count 同一 WHERE 组装（页数据与计数同口径单点，M9 惯例）。
 */
@Repository
public class PolicyScopeRepositoryImpl implements PolicyScopeRepository {

    /** 源类别常量（gov_policy 入列后政策源=5；与 InfoSourceCatalog category 字面量同源口径）。 */
    private static final String POLICY_CATEGORY = "政策";

    /** L1 监管·政策容器（scope ② 路锚，IndustryCategory 容器同字面量——仓储不依赖 analysis 域目录类）。 */
    private static final String POLICY_CONTAINER = "监管·政策";

    /** 单页上限护栏（防御线；详情分区容量 10/政策页 50 均在其内）。 */
    private static final int MAX_LIMIT = 200;

    /** days 窗时区（上海日界，与快照/事件域同口径）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final String SCOPE_FROM_SQL =
            """
              FROM news_item ni
              JOIN info_source s ON s.id = ni.source_id AND s.deleted = 0
              JOIN news_analysis na ON na.news_id = ni.id
             WHERE ni.status = 1
               AND na.l0_result = 'PASS'
            """;

    private static final String SELECT_SQL =
            """
            SELECT ni.id, ni.title, ni.summary, ni.url, ni.published_at,
                   s.source_code, s.name AS source_name, s.category AS source_category,
                   na.main_category, na.sub_industry, na.matched_subjects"""
                    + SCOPE_FROM_SQL;

    private static final String COUNT_SQL = "SELECT COUNT(*)" + SCOPE_FROM_SQL;

    private static final RowMapper<PolicyScopeRow> ROW =
            (rs, rowNum) ->
                    new PolicyScopeRow(
                            rs.getLong("id"),
                            rs.getString("title"),
                            nullable(rs.getString("summary")),
                            nullable(rs.getString("url")),
                            Instant.parse(rs.getString("published_at")),
                            rs.getString("source_code"),
                            rs.getString("source_name"),
                            rs.getString("source_category"),
                            nullable(rs.getString("main_category")),
                            nullable(rs.getString("sub_industry")),
                            nullable(rs.getString("matched_subjects")));

    private final JdbcTemplate jdbcTemplate;

    public PolicyScopeRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PolicyScopeRow> findPage(PolicyScopeFilter filter) {
        Query query = new Query(SELECT_SQL);
        applyScope(query, filter);
        applyFilters(query, filter);
        int limit = Math.max(1, Math.min(filter.limit(), MAX_LIMIT));
        query.appendRaw(" ORDER BY ni.published_at DESC, ni.id DESC");
        query.appendRaw(" LIMIT " + limit + " OFFSET " + Math.max(0, filter.offset()));
        return jdbcTemplate.query(query.sql(), ROW, query.args());
    }

    @Override
    public long count(PolicyScopeFilter filter) {
        Query query = new Query(COUNT_SQL);
        applyScope(query, filter);
        applyFilters(query, filter);
        Long total = jdbcTemplate.queryForObject(query.sql(), Long.class, query.args());
        return total == null ? 0L : total;
    }

    @Override
    public Optional<PolicyScopeRow> findById(long newsId) {
        // 详情：同口径谓词（①② 必居其一）+ id 锚；无 days 窗——历史政策条目详情可达
        Query query = new Query(SELECT_SQL);
        applyScopePredicate(query);
        query.append(" AND ni.id = ?", newsId);
        List<PolicyScopeRow> rows = jdbcTemplate.query(query.sql(), ROW, query.args());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** days 窗 + scope ①②（sourceCode 显式选源时旁路——含宏观源，ADR-0062 随批 4）+ keyset 游标。 */
    private static void applyScope(Query query, PolicyScopeFilter filter) {
        query.append(" AND ni.published_at >= ?", windowStartIso(filter.days()));
        if (isPresent(filter.sourceCode())) {
            query.append(" AND s.source_code = ?", filter.sourceCode().trim());
        } else {
            applyScopePredicate(query);
        }
        if (filter.beforeId() != null && filter.beforeId() > 0) {
            // keyset 游标：取排序位置严格早于锚条目的行（published_at DESC, id DESC 对偶条件）；
            // 锚条目已删（子查询 NULL）→ 空页（游标失效静默收敛，不报错）
            query.append(
                    " AND (ni.published_at < (SELECT p.published_at FROM news_item p WHERE p.id = ?)",
                    filter.beforeId());
            query.appendRaw(
                    " OR (ni.published_at = (SELECT p.published_at FROM news_item p WHERE p.id = ?)"
                            + " AND ni.id < ?))");
            query.argsAdd(filter.beforeId(), filter.beforeId());
        }
    }

    /** scope ①②：政策源 PASS ∪ 全源 L1=监管·政策（DONE）——REQ 拍板二口径。 */
    private static void applyScopePredicate(Query query) {
        query.append(
                " AND (s.category = ? OR (na.l1_status = 'DONE' AND na.main_category = ?))",
                POLICY_CATEGORY,
                POLICY_CONTAINER);
    }

    /** 追加过滤（全 AND）：industry / keyword / subjectCode ∪ industries（详情分区 ①② 并集）。 */
    private static void applyFilters(Query query, PolicyScopeFilter filter) {
        if (isPresent(filter.industry())) {
            String industry = filter.industry().trim();
            if (POLICY_CONTAINER.equals(industry)) {
                // 容器仅 main 命中（§4.1 行业参数语义：申万 main 或 sub / 容器仅 main）
                query.append(" AND na.main_category = ?", industry);
            } else {
                query.append(
                        " AND (na.main_category = ? OR na.sub_industry = ?)", industry, industry);
            }
        }
        if (isPresent(filter.keyword())) {
            // M9/ADR-0035 先例：LIKE 通配符转义 + ESCAPE '\'（title 或 summary 任一命中；NULL LIKE 天然不命中）
            String pattern = FeedItemRepositoryImpl.likePattern(filter.keyword().trim());
            query.append(" AND (ni.title LIKE ? ESCAPE '\\'", pattern);
            query.append(" OR ni.summary LIKE ? ESCAPE '\\')", pattern);
        }
        applySubjectUnion(query, filter);
    }

    /**
     * 标的并集谓词（详情分区拍板三）：① matched_subjects 引号定界 LIKE ∪ ② main/sub ∈ 关联集行业。 仅一路有输入时退化为单路（INDUSTRY
     * 单路独立成括号组）。
     */
    private static void applySubjectUnion(Query query, PolicyScopeFilter filter) {
        boolean hasSubject = isPresent(filter.subjectCode());
        boolean hasIndustries = filter.industries() != null && !filter.industries().isEmpty();
        if (hasSubject && hasIndustries) {
            query.append(" AND (na.matched_subjects LIKE ?", subjectToken(filter.subjectCode()));
            appendIndustryGroup(query, filter);
            query.appendRaw(")");
        } else if (hasSubject) {
            query.append(" AND na.matched_subjects LIKE ?", subjectToken(filter.subjectCode()));
        } else if (hasIndustries) {
            query.appendRaw(" AND (");
            appendIndustryGroup(query, filter);
            query.appendRaw(")");
        }
    }

    /** 行业集 IN 组（main IN (...) OR sub IN (...)，自闭合无外括号）。 */
    private static void appendIndustryGroup(Query query, PolicyScopeFilter filter) {
        String[] industries = filter.industries().toArray(new String[0]);
        StringBuilder fragment = new StringBuilder(" OR na.main_category IN (");
        appendPlaceholders(fragment, industries.length);
        fragment.append(") OR na.sub_industry IN (");
        appendPlaceholders(fragment, industries.length);
        fragment.append(")");
        String[] params = new String[industries.length * 2];
        System.arraycopy(industries, 0, params, 0, industries.length);
        System.arraycopy(industries, 0, params, industries.length, industries.length);
        query.append(fragment.toString(), (Object[]) params);
    }

    /**
     * matched_subjects JSON 值位引号定界 token（[{"code":"SH600519",...}] → 包 ":"SH600519""——
     * 冒号+引号+值+引号四字符锚定值位，方案 §3.1 引号边界语义的精确形态；实测 SQLite LIKE 校准 2026-09-27）。
     */
    private static String subjectToken(String subjectCode) {
        return "%\":\"" + subjectCode.trim() + "\"%";
    }

    /** days 窗下界（上海日界：days 天前当日 00:00 含，M9 政策页窗口语义沿用；days clamp 归服务层）。 */
    private static String windowStartIso(int days) {
        return LocalDate.now(SHANGHAI)
                .minusDays(Math.max(1, days))
                .atStartOfDay(SHANGHAI)
                .toInstant()
                .toString();
    }

    private static void appendPlaceholders(StringBuilder fragment, int count) {
        for (int i = 0; i < count; i++) {
            fragment.append(i > 0 ? ", ?" : "?");
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 参数化查询组装小件（FeedItemRepositoryImpl 同款：SQL 片段 + 顺序参数；appendRaw 仅用于受控字面量）。 */
    private static final class Query {
        private final StringBuilder sql;
        private final List<Object> args = new ArrayList<>();

        Query(String baseSql) {
            this.sql = new StringBuilder(baseSql);
        }

        void append(String fragment, Object... params) {
            sql.append(fragment);
            for (Object param : params) {
                args.add(param);
            }
        }

        void appendRaw(String fragment) {
            sql.append(fragment);
        }

        /** 追加参数（appendRaw 片段配套——片段内 ? 与参数按序对应）。 */
        void argsAdd(Object... params) {
            for (Object param : params) {
                args.add(param);
            }
        }

        String sql() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }
    }
}
