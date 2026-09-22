package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数据源事件记录器（基础设施层横切组件，T16）：在 {@code AbstractSourceAdapter} 降级路径旁路调用，落 {@code data_source_event}
 * 一条事件。
 *
 * <p>对齐技术方案 §4.3 流程 1 + §5 非功能「可观测：数据源缺失记 WARN」「降级预案：数据源挂→该分区暂无数据+记事件不阻断」。 可观测性不能拖垮主链路： {@link
 * #record} 同步落库但<b>自身异常一律 catch 记 ERROR 日志、不外抛</b>——记录失败绝不影响 adapter 降级返回值（仍 MISSING/FAILED）与聚合主流程。
 *
 * <p>调用方（{@code AbstractSourceAdapter#recordEvent}）在此基础上再做一层 try-catch
 * 兜底（defense-in-depth），保证「记录旁路、零侵入」。
 *
 * <p>实现说明：本批走<b>同步</b>落库——data_source_event 仅在降级路径（异常分支，非热路径）写入，单行 SQLite INSERT 为百毫秒级以下，
 * 同步即可满足且事务语义清晰； 后续若可观测量级上升（如限频源高频降级），可平滑切 {@code @Async}（届时需引入异步执行器 Bean 与事务边界约定，本批不引以免增复杂度）。
 *
 * <h2>T36 · OK 成功心跳（60s/源内存节流）</h2>
 *
 * <p>{@link #recordOkIfDue} 供成功路径旁路调用（方案 §4.3「OK(5) 成功心跳」）：每源 60s 至多一条 OK
 * 事件（内存时间戳节流，重启即重置）， 行情 5s TTL 高频命中下仍把表增速控制在 ~1.4k 行/日/源上限内（方案 §5 容量）。竞态双写至多多一条，量级无害。
 */
@Component
public class DataSourceEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(DataSourceEventRecorder.class);

    /** OK 心跳节流窗口（每源至多一条/窗口）。 */
    static final long OK_THROTTLE_MILLIS = 60_000;

    private final DataSourceEventRepository repository;
    private final Clock clock;

    /** 每源最近一次 OK 心跳落库时刻（epoch millis）。 */
    private final Map<SourceCode, Long> lastOkAt = new ConcurrentHashMap<>();

    public DataSourceEventRecorder(DataSourceEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 记一条数据源事件（落库）。永不抛异常：内部失败仅记 ERROR 日志。
     *
     * @param sourceCode 数据源标识（六类之一）
     * @param eventType 事件类型（缺失/超时/错误/限频/成功心跳）
     * @param subjectId 标的内部主键，允许为空
     * @param detail 人读详情，允许为空
     */
    public void record(
            SourceCode sourceCode, DataSourceEventType eventType, Long subjectId, String detail) {
        try {
            DataSourceEvent saved =
                    repository.save(
                            DataSourceEvent.create(sourceCode, eventType, subjectId, detail));
            log.debug(
                    "数据源事件已落库 id={} sourceCode={} type={} subjectId={}",
                    saved.getId(),
                    sourceCode,
                    eventType,
                    subjectId);
        } catch (Exception e) {
            // 可观测性不能拖垮主链路：落库失败记 ERROR，不外抛（调用方降级返回值不受影响）
            log.error(
                    "记录数据源事件失败 sourceCode={} type={} subjectId={} detail={}",
                    sourceCode,
                    eventType,
                    subjectId,
                    detail,
                    e);
        }
    }

    /**
     * 成功心跳（OK）：60s/源节流，窗口内重复成功不落库。永不抛异常（同 {@link #record}）。
     *
     * @param sourceCode 数据源标识
     * @param subjectId 触发本次成功的标的（展示用）
     */
    public void recordOkIfDue(SourceCode sourceCode, Long subjectId) {
        if (!okDue(sourceCode)) {
            return;
        }
        record(sourceCode, DataSourceEventType.OK, subjectId, "ok");
    }

    /** 节流判定并占位（compute 原子读改；竞态窗口至多多写一条，无害）。 */
    private boolean okDue(SourceCode sourceCode) {
        boolean[] due = {false};
        lastOkAt.compute(
                sourceCode,
                (code, last) -> {
                    long now = clock.millis();
                    if (last != null && now - last < OK_THROTTLE_MILLIS) {
                        return last;
                    }
                    due[0] = true;
                    return now;
                });
        return due[0];
    }
}
