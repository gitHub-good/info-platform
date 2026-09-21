package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.aggregation.DataSourceEvent;
import com.info.platform.domain.aggregation.DataSourceEventRepository;
import com.info.platform.domain.aggregation.DataSourceEventType;
import com.info.platform.domain.aggregation.SourceCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * DataSourceEventRepositoryImpl 集成测试（T16）：SQLite 共享内存库 + Flyway V7 建表后，测 save 往返（id/时间戳回填、枚举互转）与
 * findBySourceCodeAndTimeRange 的源过滤、时间窗、升序排序、空入参防御。 @SpringBootTest 启动完整上下文（含 Flyway
 * 迁移）；@Transactional 每用例结束回滚隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DataSourceEventRepositoryImplTest {

    @Autowired private DataSourceEventRepository dataSourceEventRepository;
    @Autowired private DataSourceEventMapper dataSourceEventMapper;

    private static final long SUBJECT_ID = 9999L;
    private static final Instant T1 = Instant.parse("2026-09-21T02:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-21T05:30:00Z");

    @Test
    void save_newEvent_backfillsIdAndTimestamps_andRoundTripsEnums() {
        // Arrange
        DataSourceEvent event =
                DataSourceEvent.create(
                        SourceCode.QUOTE,
                        DataSourceEventType.TIMEOUT,
                        SUBJECT_ID,
                        "exhausted sourceCode=QUOTE");

        // Act
        DataSourceEvent saved = dataSourceEventRepository.save(event);

        // Assert：id/时间戳回填，枚举与字段往返一致
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getSourceCode()).isEqualTo(SourceCode.QUOTE);
        assertThat(saved.getEventType()).isEqualTo(DataSourceEventType.TIMEOUT);
        assertThat(saved.getSubjectId()).contains(SUBJECT_ID);
        assertThat(saved.getDetail()).contains("exhausted sourceCode=QUOTE");
    }

    @Test
    void findBySourceCodeAndTimeRange_returnsOnlyMatchingSourceInWindowOrderedAsc() {
        // Arrange：直接经 mapper 灌入固定 createdAt 的行（确定性排序）
        //   QUOTE T1、QUOTE T2（窗内，应返回，升序）、FINANCE T1（异源，排除）、QUOTE T2+1d（窗外，排除）
        seed(SourceCode.QUOTE, DataSourceEventType.MISSING, T1);
        seed(SourceCode.QUOTE, DataSourceEventType.TIMEOUT, T2);
        seed(SourceCode.FINANCE, DataSourceEventType.ERROR, T1);
        seed(SourceCode.QUOTE, DataSourceEventType.LIMITED, T2.plusSeconds(86400));

        // Act：窗口 [T1-1m, T2+1m]
        List<DataSourceEvent> events =
                dataSourceEventRepository.findBySourceCodeAndTimeRange(
                        SourceCode.QUOTE, T1.minusSeconds(60), T2.plusSeconds(60));

        // Assert：仅 QUOTE 窗内两行，按 created_at 升序（T1 在前）
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getCreatedAt()).isEqualTo(T1);
        assertThat(events.get(1).getCreatedAt()).isEqualTo(T2);
        assertThat(events).allMatch(e -> e.getSourceCode() == SourceCode.QUOTE);
    }

    @Test
    void findBySourceCodeAndTimeRange_windowAfterAll_returnsEmpty() {
        // Arrange
        seed(SourceCode.QUOTE, DataSourceEventType.MISSING, T1);

        // Act：窗口在所有记录之后
        List<DataSourceEvent> events =
                dataSourceEventRepository.findBySourceCodeAndTimeRange(
                        SourceCode.QUOTE, T1.plusSeconds(3600), T1.plusSeconds(7200));

        // Assert
        assertThat(events).isEmpty();
    }

    @Test
    void findBySourceCodeAndTimeRange_nullArgs_returnsEmptyDefensively() {
        // Act + Assert：任一入参为空直接返回空列表（防御，避免 NPE）
        assertThat(dataSourceEventRepository.findBySourceCodeAndTimeRange(null, T1, T2)).isEmpty();
        assertThat(
                        dataSourceEventRepository.findBySourceCodeAndTimeRange(
                                SourceCode.QUOTE, null, T2))
                .isEmpty();
        assertThat(
                        dataSourceEventRepository.findBySourceCodeAndTimeRange(
                                SourceCode.QUOTE, T1, null))
                .isEmpty();
    }

    /** 经 mapper 直接灌入一行（固定 createdAt/updatedAt，绕过 repository.save 的 Instant.now()，保证排序确定性）。 */
    private void seed(SourceCode code, DataSourceEventType type, Instant createdAt) {
        DataSourceEventPO po = new DataSourceEventPO();
        po.setSourceCode(code.name());
        po.setEventType(type.code());
        po.setSubjectId(SUBJECT_ID);
        po.setDetail("seed-" + createdAt);
        po.setCreatedAt(createdAt.toString());
        po.setUpdatedAt(createdAt.toString());
        dataSourceEventMapper.insert(po);
    }
}
