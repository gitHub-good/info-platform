package com.info.platform.infrastructure.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventItemRepository;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link EventItemRepository} 端口的 SQLite 实现（M15 T122，ADR-0046 裁决 1）。
 *
 * <p>UPSERT by {@code UNIQUE(news_id)}（{@code INSERT ... ON CONFLICT(news_id) DO
 * UPDATE}——拆批重试与补跑重入幂等）； 时间列整秒 ISO-8601 UTC 文本（V22/V23 惯例）；JSON
 * 列（affected_industries/key_figures/subjects）经 Jackson 序列化，回读失败按空表降级（历史行损坏不阻断读）。
 */
@Repository
public class EventItemRepositoryImpl implements EventItemRepository {

    private static final Logger log = LoggerFactory.getLogger(EventItemRepositoryImpl.class);

    private static final String UPSERT_SQL =
            """
            INSERT INTO event_item
              (news_id, event_type, summary, affected_industries, direction, importance,
               key_figures, subjects, quote, event_time, event_date, prompt_version,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(news_id) DO UPDATE SET
              event_type = excluded.event_type,
              summary = excluded.summary,
              affected_industries = excluded.affected_industries,
              direction = excluded.direction,
              importance = excluded.importance,
              key_figures = excluded.key_figures,
              subjects = excluded.subjects,
              quote = excluded.quote,
              event_time = excluded.event_time,
              event_date = excluded.event_date,
              prompt_version = excluded.prompt_version,
              updated_at = excluded.updated_at
            """;

    private static final RowMapper<EventItem> EVENT_ROW = EventItemRowSupport.EVENT_ROW;

    /** 事件流卡片行（EVENT_ROW 复用 + news 标题/链接 join 列，列别名避让 e.* 标签）。 */
    private static final RowMapper<EventItemRepository.EventStreamItem> STREAM_ROW =
            (rs, rowNum) ->
                    new EventItemRepository.EventStreamItem(
                            EVENT_ROW.mapRow(rs, rowNum),
                            rs.getString("news_title"),
                            rs.getString("news_url"));

    private static final String FIND_STREAM_SQL =
            """
            SELECT e.*, ni.title AS news_title, ni.url AS news_url
              FROM event_item e
              JOIN news_item ni ON ni.id = e.news_id
             WHERE 1 = 1
            """;

    private static final String COUNT_STREAM_SQL =
            """
            SELECT COUNT(*)
              FROM event_item e
              JOIN news_item ni ON ni.id = e.news_id
             WHERE 1 = 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventItemRepositoryImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public EventItem upsert(EventItem item) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement ps = connection.prepareStatement(UPSERT_SQL);
                    ps.setLong(1, item.getNewsId());
                    ps.setString(2, item.getEventType().name());
                    ps.setString(3, item.getSummary());
                    ps.setString(4, toJson(item.getAffectedIndustries()));
                    ps.setString(5, item.getDirection().name());
                    ps.setString(6, item.getImportance().name());
                    ps.setString(7, toJson(item.getKeyFigures()));
                    ps.setString(8, toJson(item.getSubjects()));
                    ps.setString(9, item.getQuote());
                    ps.setString(10, isoOrNull(item.getEventTime()));
                    ps.setString(11, item.getEventDate());
                    ps.setString(12, item.getPromptVersion());
                    ps.setString(13, now);
                    ps.setString(14, now);
                    return ps;
                });
        return item;
    }

    @Override
    public List<EventItem> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                "SELECT * FROM event_item WHERE id IN (" + placeholders + ")",
                EVENT_ROW,
                ids.toArray());
    }

    @Override
    public java.util.Optional<EventItem> findByNewsId(long newsId) {
        // UNIQUE(news_id) 至多一条；V2.3-M23 T201 政策详情 relatedEvents 数据面
        List<EventItem> rows =
                jdbcTemplate.query("SELECT * FROM event_item WHERE news_id = ?", EVENT_ROW, newsId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    @Override
    public List<EventItemRepository.EventStreamItem> findStreamItemsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                "SELECT e.*, ni.title AS news_title, ni.url AS news_url"
                        + " FROM event_item e LEFT JOIN news_item ni ON ni.id = e.news_id"
                        + " WHERE e.id IN ("
                        + placeholders
                        + ")",
                STREAM_ROW,
                ids.toArray());
    }

    @Override
    public List<EventItemRepository.EventStreamItem> findStreamItems(
            EventItemRepository.EventStreamFilter filter, Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder(FIND_STREAM_SQL);
        List<Object> args = new ArrayList<>();
        appendStreamFilters(sql, args, filter);
        if (beforeId != null) {
            sql.append(" AND e.id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY e.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), STREAM_ROW, args.toArray());
    }

    @Override
    public long countStreamItems(EventItemRepository.EventStreamFilter filter) {
        StringBuilder sql = new StringBuilder(COUNT_STREAM_SQL);
        List<Object> args = new ArrayList<>();
        appendStreamFilters(sql, args, filter);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<EventItemRepository.EventStreamItem> findStreamItemsPaged(
            EventItemRepository.EventStreamFilter filter, int page, int size) {
        // M25 T220：同 WHERE/同序（id DESC）的 offset 窗口；offset 上界由 PageQuery.MAX_PAGE 保证不溢出 int
        StringBuilder sql = new StringBuilder(FIND_STREAM_SQL);
        List<Object> args = new ArrayList<>();
        appendStreamFilters(sql, args, filter);
        sql.append(" ORDER BY e.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add((page - 1) * size);
        return jdbcTemplate.query(sql.toString(), STREAM_ROW, args.toArray());
    }

    /**
     * 五维筛选拼装（null 维度跳过；行业 = affected JSON 引号定界 LIKE，防「非银金融」子串误配； market = subjects JSON code
     * 前缀定界 LIKE——M29 §5.4 标的市场过滤，code 线格式 SH/SZ/HK/US 前缀（T254 marketOfCode 口径），JSON 值域引号转义不含
     * {@code "code":"HK} 形态子串、枚举名不含 LIKE 通配符）。
     */
    private static void appendStreamFilters(
            StringBuilder sql, List<Object> args, EventItemRepository.EventStreamFilter filter) {
        if (filter.eventType() != null) {
            sql.append(" AND e.event_type = ?");
            args.add(filter.eventType().name());
        }
        if (filter.industry() != null) {
            sql.append(" AND e.affected_industries LIKE ?");
            args.add("%\"" + filter.industry() + "\"%");
        }
        if (filter.importance() != null) {
            sql.append(" AND e.importance = ?");
            args.add(filter.importance().name());
        }
        if (filter.direction() != null) {
            sql.append(" AND e.direction = ?");
            args.add(filter.direction().name());
        }
        if (filter.market() != null) {
            sql.append(" AND e.subjects LIKE ?");
            args.add("%\"code\":\"" + filter.market().name() + "%");
        }
    }

    // —— JSON 列编解码 ——

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            log.warn("event_item JSON 序列化失败（落空表）: {}", e.getMessage());
            return "[]";
        }
    }

    private static String isoOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
