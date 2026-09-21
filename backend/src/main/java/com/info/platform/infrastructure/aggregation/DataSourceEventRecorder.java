package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
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
 */
@Component
public class DataSourceEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(DataSourceEventRecorder.class);

    private final DataSourceEventRepository repository;

    public DataSourceEventRecorder(DataSourceEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 记一条数据源事件（落库）。永不抛异常：内部失败仅记 ERROR 日志。
     *
     * @param sourceCode 数据源标识（六类之一）
     * @param eventType 事件类型（缺失/超时/错误/限频）
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
}
