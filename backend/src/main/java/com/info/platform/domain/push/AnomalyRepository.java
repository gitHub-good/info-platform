package com.info.platform.domain.push;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 异动记录仓储端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>领域层纯净接口，不依赖 MyBatis/Spring 等框架类型。
 *
 * <h2>业务键去重</h2>
 *
 * anomaly_event 无 UNIQUE 约束（§4.2 DDL 未定义），去重靠 {@link #existsByBusinessKey} 应用层查重： 业务键 = {@code
 * subjectId + anomalyType + triggerDate}（同标的同类型同日只记一条）。查询以 trigger_time 的当日范围 （{@code >= 日始 AND <
 * 次日}）落在索引 {@code idx_anomaly_subject_time} 上。
 *
 * <h2>读取端口</h2>
 *
 * {@link #findPending} 供 T14 {@code PushService} 拾取未推送记录（pushed=0）补推/重连补拉； {@link #findBySubjectId}
 * 供按标的回查历史异动。
 */
public interface AnomalyRepository {

    /**
     * 落库：id 为空走 INSERT 并回填主键/时间戳，非空走 UPDATE（无乐观锁，按 §4.2 DDL 无 version）。
     *
     * @return 落库后的记录（含回填 id/createdAt/updatedAt）
     */
    AnomalyRecord save(AnomalyRecord record);

    /**
     * 业务键查重：同标的同类型同日是否已存在异动记录。
     *
     * @param subjectId 标的内部主键
     * @param anomalyType 异动类型
     * @param triggerDate 触发日（自然日，UTC），用作 trigger_time 的当日范围边界
     * @return true 表示当日已存在（应跳过，不重复入库）
     */
    boolean existsByBusinessKey(Long subjectId, AnomalyType anomalyType, LocalDate triggerDate);

    /** 未推送记录（pushed=0），按触发时间升序，供 T14 补推。 */
    List<AnomalyRecord> findPending();

    /**
     * 统计 {@code trigger_time} 落 [since, ∞) 的行数（T42 概览「今日异动条数」，方案 §4.6 今日日界口径）。
     *
     * <p>边界转 ISO-8601 整秒文本后字典序比较，落在索引 {@code idx_anomaly_time}（V15）上做范围扫描。
     */
    long countTriggeredSince(Instant since);

    /** 按标的查异动历史，按触发时间降序。 */
    List<AnomalyRecord> findBySubjectId(Long subjectId);

    /**
     * 7 天窗内按标的精确 count：{@code trigger_time >= since}（M12 T91 事件分区分页，方案 §4.2 端口扩展 3）。
     *
     * <p>边界转 ISO-8601 整秒文本后字典序比较，落在索引 {@code idx_anomaly_subject_time} 上做范围扫描 ——与页切片
     * {@link #findRecentPage} 同窗口同口径（窗界=请求时刻-7d，由调用方现算）；count 与切片非同快照的微错位接受
     * （ADR-0035 同款已知限制）。
     */
    long countRecentBySubject(Long subjectId, Instant since);

    /**
     * 窗口内分页取数：{@code trigger_time >= since}，按 {@code trigger_time} 倒序 LIMIT/OFFSET（ADR-0035 从简口径）。
     *
     * @param offset 偏移量（(page-1)×size，由调用方换算）
     * @param limit 页大小（1~50，接口层已校验）
     */
    List<AnomalyRecord> findRecentPage(Long subjectId, Instant since, int offset, int limit);
}
