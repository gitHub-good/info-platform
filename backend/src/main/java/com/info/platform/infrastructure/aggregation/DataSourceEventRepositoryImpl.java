package com.info.platform.infrastructure.aggregation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DataSourceEventRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层，T16）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（保证 created_at 字典序即时间序，范围扫描命中索引）。
 *
 * <p>data_source_event 为追加型流水：{@link #save} 恒走 INSERT 并回填主键/时间戳（无 UPDATE 分支，与 anomaly_event 的
 * pushed 翻转路径不同）。
 *
 * <h2>范围查询</h2>
 *
 * {@link #findBySourceCodeAndTimeRange} 以 {@code from}/ {@code to}（UTC 整秒 Instant）转 ISO-8601 整秒字符串，
 * 落在索引 {@code idx_dse_source_time(source_code, created_at)} 上做 {@code >= / <} 字符串比较。 因边界与
 * created_at 均整秒、定长、统一 {@code Z} 后缀，字典序与时间序一致，比较结果正确（与 {@code
 * AnomalyRepositoryImpl.existsByBusinessKey} 同理）。
 */
@Repository
public class DataSourceEventRepositoryImpl implements DataSourceEventRepository {

    private static final Logger log = LoggerFactory.getLogger(DataSourceEventRepositoryImpl.class);

    private final DataSourceEventMapper dataSourceEventMapper;

    public DataSourceEventRepositoryImpl(DataSourceEventMapper dataSourceEventMapper) {
        this.dataSourceEventMapper = dataSourceEventMapper;
    }

    @Override
    @Transactional
    public DataSourceEvent save(DataSourceEvent event) {
        DataSourceEventPO po = toPO(event);
        String now = Instant.now().toString();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        dataSourceEventMapper.insert(po);
        log.info(
                "记录数据源事件: id={}, sourceCode={}, type={}, subjectId={}, detail={}",
                po.getId(),
                po.getSourceCode(),
                po.getEventType(),
                po.getSubjectId(),
                po.getDetail());
        return toEntity(po);
    }

    @Override
    public List<DataSourceEvent> findBySourceCodeAndTimeRange(
            SourceCode sourceCode, Instant from, Instant to) {
        if (sourceCode == null || from == null || to == null) {
            return Collections.emptyList();
        }
        List<DataSourceEventPO> pos =
                dataSourceEventMapper.selectList(
                        new LambdaQueryWrapper<DataSourceEventPO>()
                                .eq(DataSourceEventPO::getSourceCode, sourceCode.name())
                                .ge(DataSourceEventPO::getCreatedAt, from.toString())
                                .lt(DataSourceEventPO::getCreatedAt, to.toString())
                                .orderByAsc(DataSourceEventPO::getCreatedAt));
        return toEntities(pos);
    }

    private List<DataSourceEvent> toEntities(List<DataSourceEventPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(DataSourceEventRepositoryImpl::toEntity).toList();
    }

    private static DataSourceEvent toEntity(DataSourceEventPO po) {
        return DataSourceEvent.reconstruct(
                po.getId(),
                SourceCode.valueOf(po.getSourceCode()),
                DataSourceEventType.fromCode(po.getEventType()),
                po.getSubjectId(),
                po.getDetail(),
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static DataSourceEventPO toPO(DataSourceEvent event) {
        DataSourceEventPO po = new DataSourceEventPO();
        po.setId(event.getId());
        po.setSourceCode(event.getSourceCode().name());
        po.setEventType(event.getEventType().code());
        po.setSubjectId(event.getSubjectId().orElse(null));
        po.setDetail(event.getDetail().orElse(null));
        po.setCreatedAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString());
        po.setUpdatedAt(event.getUpdatedAt() == null ? null : event.getUpdatedAt().toString());
        return po;
    }
}
