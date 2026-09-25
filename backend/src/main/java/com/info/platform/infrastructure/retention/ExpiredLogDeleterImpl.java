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
 * <p>亚秒边界（D1 修复）：四表 created_at 实存 {@code Instant.toString()} 变长亚秒文本（如 {@code
 * …T07:47:37.552745400Z}）， 与整秒 cutoff 串直接字典序比较时 {@code '.'(0x2E) <
 * 'Z'(0x5A)}，边界秒内的行会被误判过期。比较侧改用「秒前缀 + {@code .}」作下界（见 {@link #secondLowerBound(Instant)}），语义等价于行侧
 * {@code substr(created_at,1,19)} 截断比较， 但谓词保持 {@code created_at < ?} 形态不破坏索引可用性。
 *
 * <p>事务边界：单次调用即一批（调用方每批一次、循环至返回值 &lt; limit）——Spring 代理下每条 JdbcTemplate 语句独立提交， 对齐
 * ADR-0036「批间独立事务」（500 行毫秒级提交，WAL 写锁持有窗口短）。
 */
@Component
public class ExpiredLogDeleterImpl implements ExpiredLogDeleter {

    /** ISO-8601 整秒时刻文本去掉结尾 {@code Z} 后的秒前缀长度（{@code yyyy-MM-ddTHH:mm:ss}）。 */
    private static final int SECOND_PREFIX_LENGTH = 19;

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
        String bound = secondLowerBound(cutoff);
        String name = table.physicalName();
        String sql =
                "DELETE FROM "
                        + name
                        + " WHERE id IN ("
                        + "SELECT id FROM "
                        + name
                        + " WHERE created_at < ? LIMIT ?)";
        return jdbcTemplate.update(sql, bound, limit);
    }

    /**
     * 删除比较下界：cutoff 整秒截断后取「秒前缀 + {@code .}」（如 {@code 2026-08-23T00:00:00.}，防御重复——调用方已
     * 截断，此处保证端口自身语义完整）。
     *
     * <p>为什么补 {@code .} 而非直接用整秒串（D1）：created_at 为变长亚秒文本，同一秒内的任何形态（{@code …00Z} / {@code …00.5Z} /
     * {@code …00.900Z}）字典序都<b>大于</b>该下界（{@code Z}/{数字} 在第 20 位上大于 {@code .}，或前 19 位相同而 行更长）→
     * 整个边界秒保留；更早的秒在 19 字符定宽前缀内即分出大小（ISO-8601 字典序=时间序）→ 删除。 亚秒粒度上属「宁少删不误删」： 边界秒内早于精确 cutoff
     * 的部分行顺延下一轮（&lt;1s 行），方向安全。
     */
    private static String secondLowerBound(Instant cutoff) {
        String second = cutoff.truncatedTo(ChronoUnit.SECONDS).toString();
        return second.substring(0, SECOND_PREFIX_LENGTH) + ".";
    }
}
