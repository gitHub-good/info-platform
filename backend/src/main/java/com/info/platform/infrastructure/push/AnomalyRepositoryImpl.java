package com.info.platform.infrastructure.push;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AnomalyRepository} 端口的 SQLite/MyBatis-Plus 实现（基础设施层）。
 *
 * <p>PO↔Entity 转换集中于此；时间戳存 ISO-8601 整秒文本（保证 trigger_time 字典序即时间序，范围扫描命中索引）。
 *
 * <h2>业务键去重</h2>
 *
 * {@link #existsByBusinessKey} 以 triggerDate（约定为 UTC 自然日）转 [日始, 次日) 的 ISO-8601 整秒字符串范围， 落在索引 {@code
 * idx_anomaly_subject_time(subject_id, trigger_time)} 上做 {@code >= / <} 字符串比较。因 trigger_time
 * 与边界均整秒、 定长、统一 {@code Z} 后缀，字典序与时间序一致，比较结果正确。
 */
@Repository
public class AnomalyRepositoryImpl implements AnomalyRepository {

    private static final Logger log = LoggerFactory.getLogger(AnomalyRepositoryImpl.class);

    private final AnomalyMapper anomalyMapper;

    public AnomalyRepositoryImpl(AnomalyMapper anomalyMapper) {
        this.anomalyMapper = anomalyMapper;
    }

    @Override
    @Transactional
    public AnomalyRecord save(AnomalyRecord record) {
        AnomalyRecordPO po = toPO(record);
        String now = Instant.now().toString();
        if (po.getId() == null) {
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            if (po.getPushed() == null) {
                po.setPushed(0);
            }
            anomalyMapper.insert(po);
            log.info(
                    "新增异动记录: id={}, subjectId={}, type={}, changePct={}, triggerTime={}",
                    po.getId(),
                    po.getSubjectId(),
                    po.getAnomalyType(),
                    po.getChangePct(),
                    po.getTriggerTime());
            return toEntity(po);
        }
        // UPDATE（T14 推送后 markPushed 走此分支；无 version，按 id 更新）
        po.setUpdatedAt(now);
        anomalyMapper.updateById(po);
        log.info("更新异动记录: id={}, pushed={}", po.getId(), po.getPushed());
        return toEntity(po);
    }

    @Override
    public boolean existsByBusinessKey(
            Long subjectId, AnomalyType anomalyType, LocalDate triggerDate) {
        if (triggerDate == null) {
            return false;
        }
        // UTC 自然日的 [日始, 次日) 范围，整秒 ISO-8601 字符串，字典序即时间序
        String dayStart = triggerDate.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        String dayEnd = triggerDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        return anomalyMapper.exists(
                new LambdaQueryWrapper<AnomalyRecordPO>()
                        .eq(AnomalyRecordPO::getSubjectId, subjectId)
                        .eq(AnomalyRecordPO::getAnomalyType, anomalyType.code())
                        .ge(AnomalyRecordPO::getTriggerTime, dayStart)
                        .lt(AnomalyRecordPO::getTriggerTime, dayEnd));
    }

    @Override
    public List<AnomalyRecord> findPending() {
        List<AnomalyRecordPO> pos =
                anomalyMapper.selectList(
                        new LambdaQueryWrapper<AnomalyRecordPO>()
                                .eq(AnomalyRecordPO::getPushed, 0)
                                .orderByAsc(AnomalyRecordPO::getTriggerTime));
        return toEntities(pos);
    }

    @Override
    public List<AnomalyRecord> findBySubjectId(Long subjectId) {
        List<AnomalyRecordPO> pos =
                anomalyMapper.selectList(
                        new LambdaQueryWrapper<AnomalyRecordPO>()
                                .eq(AnomalyRecordPO::getSubjectId, subjectId)
                                .orderByDesc(AnomalyRecordPO::getTriggerTime));
        return toEntities(pos);
    }

    private List<AnomalyRecord> toEntities(List<AnomalyRecordPO> pos) {
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(AnomalyRepositoryImpl::toEntity).toList();
    }

    private static AnomalyRecord toEntity(AnomalyRecordPO po) {
        return AnomalyRecord.reconstruct(
                po.getId(),
                po.getSubjectId(),
                AnomalyType.fromCode(po.getAnomalyType()),
                po.getChangePct(),
                po.getCurrentPrice(),
                po.getTriggerTime() == null ? null : Instant.parse(po.getTriggerTime()),
                po.getDetail(),
                po.getPushed() != null && po.getPushed() == 1,
                po.getCreatedAt() == null ? null : Instant.parse(po.getCreatedAt()),
                po.getUpdatedAt() == null ? null : Instant.parse(po.getUpdatedAt()));
    }

    private static AnomalyRecordPO toPO(AnomalyRecord record) {
        AnomalyRecordPO po = new AnomalyRecordPO();
        po.setId(record.getId());
        po.setSubjectId(record.getSubjectId());
        po.setAnomalyType(record.getAnomalyType().code());
        po.setChangePct(record.getChangePct().orElse(null));
        po.setCurrentPrice(record.getCurrentPrice().orElse(null));
        po.setTriggerTime(
                record.getTriggerTime() == null ? null : record.getTriggerTime().toString());
        po.setDetail(record.getDetail().orElse(null));
        po.setPushed(record.isPushed() ? 1 : 0);
        po.setCreatedAt(record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
        po.setUpdatedAt(record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString());
        return po;
    }
}
