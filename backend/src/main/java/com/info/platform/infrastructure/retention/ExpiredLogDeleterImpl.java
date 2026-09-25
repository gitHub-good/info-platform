package com.info.platform.infrastructure.retention;

import com.info.platform.domain.retention.ExpiredLogDeleter;
import com.info.platform.domain.retention.RetentionLogTable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ExpiredLogDeleter} 实现（T70，ADR-0036 §3）：JdbcTemplate 子查询分批 DELETE。
 *
 * <p>SQL 形态 {@code DELETE FROM {t} WHERE id IN (SELECT id FROM {t} WHERE created_at < ? LIMIT ?)}——
 * SQLite 默认构建未开 {@code SQLITE_ENABLE_UPDATE_DELETE_LIMIT}，{@code DELETE … LIMIT} 直写不可用；子查询形态可移植且
 * 内层可走 created_at 索引。表名取自枚举常量（编译期定死）拼接 SQL 骨架，cutoff/limit 一律 {@code ?} 绑定，无注入面。
 *
 * <p>事务边界：单次调用即一批（调用方每批一次、循环至返回值 &lt; limit）——Spring 代理下每条 JdbcTemplate 语句独立提交，
 * 对齐 ADR-0036「批间独立事务」（500 行毫秒级提交，WAL 写锁持有窗口短）。
 */
@Component
public class ExpiredLogDeleterImpl implements ExpiredLogDeleter {

    private final JdbcTemplate jdbcTemplate;

    public ExpiredLogDeleterImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long deleteExpiredBefore(RetentionLogTable table, Instant cutoff, int limit) {
        Objects.requireNonNull(table, "table 必填");
        Objects.requireNonNull(cutoff, "cutoff 必填");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 须为正整数: " + limit);
        }
        // 整秒截断（防御重复：调用方已截断，此处保证端口自身语义完整）——两侧同为整秒 ISO-8601 定长文本，字典序=时间序
        String bound = cutoff.truncatedTo(ChronoUnit.SECONDS).toString();
        String name = table.physicalName();
        String sql =
                "DELETE FROM " + name + " WHERE id IN ("
                        + "SELECT id FROM " + name + " WHERE created_at < ? LIMIT ?)";
        return jdbcTemplate.update(sql, bound, limit);
    }
}
