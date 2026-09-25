package com.info.platform.domain.feed;

import java.time.Instant;
import java.util.Optional;

/**
 * 源轮询状态仓储端口（{@code source_poll_state}，M13 T100）。
 *
 * <p>写路径高频（每轮两次）：种子初始化与轮次推进；轮次推进须与条目落库、统计自增同事务（调用方以事务模板包裹）。
 */
public interface SourcePollStateRepository {

    /** 按源查找运行态（1:1；新源落库前可能尚无行）。 */
    Optional<SourcePollState> findBySourceId(Long sourceId);

    /**
     * seed-if-absent 初始化运行态行（已存在不动）。
     *
     * @param nextDueAt 首次应抓时刻（含错峰偏移；新源由注册服务设 now）
     */
    boolean insertIfAbsent(Long sourceId, Instant nextDueAt, Instant now);

    /** 全字段更新运行态（by source_id；轮次推进，须在条目同事务内调用）。 */
    boolean update(SourcePollState state);
}
