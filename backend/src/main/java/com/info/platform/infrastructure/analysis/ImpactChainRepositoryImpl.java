package com.info.platform.infrastructure.analysis;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.ImpactCacheState;
import com.info.platform.domain.analysis.ImpactChainRepository;
import com.info.platform.domain.analysis.IndustryImpactChain;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ImpactChainRepository} 端口的 SQLite 实现（M17 T144，V28）：整事件替换（事务内 DELETE + 批量 INSERT——重生成幂等，
 * UNIQUE(event_id, industry) 最后防线）；读回按 industry ASC 稳定序。时间戳整秒 ISO-8601（UTC）文本（V23 表惯例）。
 */
@Repository
public class ImpactChainRepositoryImpl implements ImpactChainRepository {

    private static final String DELETE_SQL = "DELETE FROM industry_impact_chain WHERE event_id = ?";

    private static final String INSERT_SQL =
            """
            INSERT INTO industry_impact_chain
              (event_id, industry, direction, logic_chain, basis, template_key, gen_method,
               cache_state, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL =
            """
            SELECT id, event_id, industry, direction, logic_chain, basis, template_key,
                   gen_method, cache_state, created_at, updated_at
              FROM industry_impact_chain
             WHERE event_id = ?
             ORDER BY industry ASC
            """;

    private static final RowMapper<IndustryImpactChain> ROW_MAPPER =
            (rs, rowNum) ->
                    IndustryImpactChain.reconstruct(
                            rs.getLong("id"),
                            rs.getLong("event_id"),
                            rs.getString("industry"),
                            Direction.fromName(rs.getString("direction")),
                            rs.getString("logic_chain"),
                            rs.getString("basis"),
                            rs.getString("template_key"),
                            ImpactCacheState.valueOf(rs.getString("cache_state")),
                            parseInstant(rs.getString("created_at")),
                            parseInstant(rs.getString("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate transactionTemplate;

    public ImpactChainRepositoryImpl(
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public int replaceForEvent(long eventId, List<IndustryImpactChain> chains) {
        Integer inserted =
                transactionTemplate.execute(
                        status -> {
                            jdbcTemplate.update(DELETE_SQL, eventId);
                            if (chains.isEmpty()) {
                                return 0;
                            }
                            int[][] batch =
                                    jdbcTemplate.batchUpdate(
                                            INSERT_SQL,
                                            chains,
                                            chains.size(),
                                            (PreparedStatement ps, IndustryImpactChain chain) -> {
                                                String now = isoOf(chain.getCreatedAt());
                                                ps.setLong(1, eventId);
                                                ps.setString(2, chain.getIndustry());
                                                ps.setString(3, chain.getDirection().name());
                                                ps.setString(4, chain.getLogicChain());
                                                ps.setString(5, chain.getBasis());
                                                ps.setString(6, chain.getTemplateKey());
                                                ps.setString(7, chain.getGenMethod());
                                                ps.setString(8, chain.getCacheState().name());
                                                ps.setString(9, now);
                                                ps.setString(10, now);
                                            });
                            return sumOf(batch);
                        });
        return inserted == null ? 0 : inserted;
    }

    @Override
    public List<IndustryImpactChain> findByEventId(long eventId) {
        return jdbcTemplate.query(FIND_SQL, ROW_MAPPER, eventId);
    }

    private static int sumOf(int[][] batch) {
        int total = 0;
        for (int[] statementCounts : batch) {
            for (int count : statementCounts) {
                if (count != Statement.EXECUTE_FAILED) {
                    total += count;
                }
            }
        }
        return total;
    }

    private static String isoOf(Instant instant) {
        return (instant == null ? Instant.now() : instant)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString();
    }

    private static Instant parseInstant(String iso) {
        return iso == null ? null : Instant.parse(iso);
    }
}
