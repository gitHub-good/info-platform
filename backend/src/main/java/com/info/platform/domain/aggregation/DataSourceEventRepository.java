package com.info.platform.domain.aggregation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 数据源事件仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>写入端口</h2>
 *
 * {@link #save} 供 {@code DataSourceEventRecorder}（基础设施层，T16）在 {@code AbstractSourceAdapter}
 * 降级路径旁路调用， 落库一条事件。data_source_event 为追加型流水，{@code save} 恒走 INSERT 并回填主键/时间戳（无 UPDATE 分支）。
 *
 * <h2>读取端口</h2>
 *
 * {@link #findBySourceCodeAndTimeRange} 供可观测/告警按源 + 时间窗回查连续失败（§5 告警=数据源连续失败）； {@code from}/ {@code
 * to} 为 UTC 整秒 Instant，转 ISO-8601 字符串后字典序即时间序，落在索引 {@code idx_dse_source_time} 上做范围扫描。
 */
public interface DataSourceEventRepository {

    /**
     * 落库：恒走 INSERT（追加型流水，无 UPDATE），回填 id/createdAt/updatedAt。
     *
     * @return 落库后的事件（含回填 id/createdAt/updatedAt）
     */
    DataSourceEvent save(DataSourceEvent event);

    /**
     * 按源 + 时间窗回查事件，按 created_at 升序（便于连续失败统计）。
     *
     * @param sourceCode 数据源标识
     * @param from 起始时刻（含，UTC 整秒）
     * @param to 截止时刻（不含，UTC 整秒）
     * @return 命中事件列表，无命中返回空列表
     */
    List<DataSourceEvent> findBySourceCodeAndTimeRange(
            SourceCode sourceCode, Instant from, Instant to);

    /**
     * 取该源最近一条事件（含 T36 OK 心跳，任意类型；健康徽章「最近一次抓取结果」口径）。
     *
     * @return 该源从未产生事件时返回空（前端「暂无抓取记录」空态）
     */
    Optional<DataSourceEvent> findLatestBySourceCode(SourceCode sourceCode);

    /**
     * 统计该源自 from 起的异常事件数（type ∈ MISSING/TIMEOUT/ERROR/LIMITED，即 1~4；OK 心跳不计，方案 §4.6 errors24h 口径）。
     */
    long countErrorsSince(SourceCode sourceCode, Instant from);
}
