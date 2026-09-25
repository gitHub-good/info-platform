package com.info.platform.domain.retention;

import java.time.Instant;

/**
 * 过期留痕删除端口（T70，ADR-0036 §3）：删除 {@code created_at} <b>严格早于</b> cutoff 的行，至多 {@code limit} 行。
 *
 * <p>签名收 {@link RetentionLogTable} 枚举不收字符串——业务表名无从传入，白名单编译期定死；cutoff/limit 由实现 {@code ?}
 * 参数绑定，无注入面。四表现有仓储端口不扩此能力（删除是横切生命周期关注点，集中一处可审计）。
 *
 * <p>判定语义：cutoff 先<b>整秒截断</b>再与 created_at（整秒 ISO-8601 定长文本）做字符串比较——两侧同格式下字典序即
 * 时间序，相等即保留（严格早于才删）；分批由调用方循环驱动（每批独立事务，批大小见应用层 RetentionPolicy）。
 */
public interface ExpiredLogDeleter {

    /**
     * 删除该表中 created_at 严格早于 cutoff 的行（至多 limit 行）。
     *
     * @param table 目标留痕表（枚举白名单，非空）
     * @param cutoff 删除界（亚秒精度将被整秒截断后判定；created_at 恰等于截断后值的行保留）
     * @param limit 本批至多删除行数（正整数；返回值小于 limit 表示已无过期行）
     * @return 本批实际删除行数
     * @throws NullPointerException table/cutoff 为 null
     * @throws IllegalArgumentException limit 非正
     */
    long deleteExpiredBefore(RetentionLogTable table, Instant cutoff, int limit);
}
