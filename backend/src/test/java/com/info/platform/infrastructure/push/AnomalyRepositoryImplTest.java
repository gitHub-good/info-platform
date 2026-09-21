package com.info.platform.infrastructure.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * AnomalyRepositoryImpl 集成测试（T13）：SQLite 共享内存库 + Flyway V5 建表后，测 save 往返、
 * 业务键去重（同/异日、同/异标的、同/异类型）、findPending/findBySubjectId 排序与 markPushed 更新路径。 @SpringBootTest
 * 启动完整上下文（含 Flyway 迁移）；@Transactional 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnomalyRepositoryImplTest {

    @Autowired private AnomalyRepository anomalyRepository;

    private static final long SUBJECT_ID = 7777L;
    private static final Instant T1 = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-21T05:30:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private AnomalyRecord newRecord(Instant triggerTime, BigDecimal changePct) {
        return AnomalyRecord.create(
                SUBJECT_ID,
                AnomalyType.PRICE_CHANGE,
                changePct,
                new BigDecimal("1680.50"),
                triggerTime,
                "日涨跌幅 " + changePct + "%");
    }

    @Test
    void save_newRecord_backfillsIdAndTimestamps_pushedFalse() {
        // Arrange
        AnomalyRecord record = newRecord(T1, new BigDecimal("5.00"));

        // Act
        AnomalyRecord saved = anomalyRepository.save(record);

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.isPushed()).isFalse();
        assertThat(saved.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(saved.getAnomalyType()).isEqualTo(AnomalyType.PRICE_CHANGE);
        assertThat(saved.getChangePct()).contains(new BigDecimal("5.00"));
        assertThat(saved.getTriggerTime()).isEqualTo(T1);
    }

    @Test
    void existsByBusinessKey_sameDayAfterInsert_returnsTrue() {
        // Arrange：当日插入一条
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));

        // Act + Assert：同标的同类型同日 → true（应去重）
        assertThat(anomalyRepository.existsByBusinessKey(SUBJECT_ID, AnomalyType.PRICE_CHANGE, DAY))
                .isTrue();
    }

    @Test
    void existsByBusinessKey_differentDay_returnsFalse() {
        // Arrange
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));

        // Act + Assert：前一天无记录 → false
        assertThat(
                        anomalyRepository.existsByBusinessKey(
                                SUBJECT_ID, AnomalyType.PRICE_CHANGE, DAY.minusDays(1)))
                .isFalse();
        // 次日无记录 → false
        assertThat(
                        anomalyRepository.existsByBusinessKey(
                                SUBJECT_ID, AnomalyType.PRICE_CHANGE, DAY.plusDays(1)))
                .isFalse();
    }

    @Test
    void existsByBusinessKey_differentSubject_returnsFalse() {
        // Arrange
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));

        // Act + Assert：同日但不同标的 → false
        assertThat(anomalyRepository.existsByBusinessKey(8888L, AnomalyType.PRICE_CHANGE, DAY))
                .isFalse();
    }

    @Test
    void existsByBusinessKey_differentType_returnsFalse() {
        // Arrange：插入 PRICE_CHANGE
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));

        // Act + Assert：同标的同日但不同类型（VOLUME 预留）→ false（不同类型各自去重）
        assertThat(anomalyRepository.existsByBusinessKey(SUBJECT_ID, AnomalyType.VOLUME, DAY))
                .isFalse();
    }

    @Test
    void existsByBusinessKey_nullDate_returnsFalse() {
        // Act + Assert：date 为空直接返回 false（防御）
        assertThat(
                        anomalyRepository.existsByBusinessKey(
                                SUBJECT_ID, AnomalyType.PRICE_CHANGE, null))
                .isFalse();
    }

    @Test
    void findPending_returnsOnlyUnpushedOrderedByTriggerTimeAsc() {
        // Arrange：两条未推记录（T2 在前插入但 T1 时间更早，验证按 trigger_time 升序）
        anomalyRepository.save(newRecord(T2, new BigDecimal("4.00")));
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));

        // Act
        List<AnomalyRecord> pending = anomalyRepository.findPending();

        // Assert：仅未推、按触发时间升序（T1 在前）
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getTriggerTime()).isEqualTo(T1);
        assertThat(pending.get(1).getTriggerTime()).isEqualTo(T2);
        assertThat(pending).allMatch(r -> !r.isPushed());
    }

    @Test
    void save_updatePath_markPushed_thenFindPendingExcludesIt() {
        // Arrange：插入未推记录
        AnomalyRecord saved = anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));
        assertThat(anomalyRepository.findPending()).hasSize(1);

        // Act：markPushed 后走 update 分支
        saved.markPushed();
        AnomalyRecord updated = anomalyRepository.save(saved);

        // Assert：pushed=true，findPending 不再包含它
        assertThat(updated.isPushed()).isTrue();
        assertThat(anomalyRepository.findPending()).isEmpty();
    }

    @Test
    void findBySubjectId_returnsRecordsOrderedDesc() {
        // Arrange
        anomalyRepository.save(newRecord(T1, new BigDecimal("5.00")));
        anomalyRepository.save(newRecord(T2, new BigDecimal("4.00")));

        // Act
        List<AnomalyRecord> records = anomalyRepository.findBySubjectId(SUBJECT_ID);

        // Assert：按 trigger_time 降序（T2 在前）
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getTriggerTime()).isEqualTo(T2);
        assertThat(records.get(1).getTriggerTime()).isEqualTo(T1);
    }
}
