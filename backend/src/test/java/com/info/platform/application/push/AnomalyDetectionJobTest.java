package com.info.platform.application.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.push.AnomalyDetectedEvent;
import com.info.platform.domain.push.AnomalyRecord;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.domain.push.AnomalyType;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * AnomalyDetectionJob 单元测试（T13）：mock 全部外部依赖（行情 adapter/仓储/事件发布器/时钟）， 直接调 {@code detectForItem} /
 * {@code detect} 验证阈值判定、边界、去重、降级、异常不阻断各分支。 @Scheduled 不触发（对齐 04 测试规范）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnomalyDetectionJobTest {

    private static final long SUBJECT_ID = 600519L;
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T02:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TRIGGER_DATE = LocalDate.of(2026, 9, 21);
    private static final Instant TRIGGER_TIME = Instant.parse("2026-09-21T02:00:00Z");

    @Mock private WatchlistRepository watchlistRepository;
    @Mock private SubjectRepository subjectRepository;
    @Mock private SourceAdapter quoteAdapter;
    @Mock private AnomalyRepository anomalyRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AnomalyDetectionJob job;
    private Subject subject;

    @BeforeEach
    void setUp() {
        when(quoteAdapter.sourceCode()).thenReturn(SourceCode.QUOTE);
        // save 回填：直接返回入参实体（id/字段齐全），供 Job 读字段构造事件
        when(anomalyRepository.save(any(AnomalyRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        job =
                new AnomalyDetectionJob(
                        watchlistRepository,
                        subjectRepository,
                        List.of(quoteAdapter),
                        anomalyRepository,
                        eventPublisher,
                        FIXED_CLOCK);
        subject =
                Subject.reconstruct(
                        SUBJECT_ID,
                        SubjectCode.of("SH600519"),
                        Market.A_SHARE,
                        SubjectType.STOCK,
                        "贵州茅台",
                        Map.of(),
                        "白酒",
                        SubjectStatus.ENABLED,
                        0L,
                        null,
                        null);
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject));
    }

    private WatchlistItem itemWithThreshold(BigDecimal threshold) {
        return WatchlistItem.create(1L, SUBJECT_ID, threshold);
    }

    private SourceResult quoteOk(String changePct, String price) {
        return SourceResult.ok(
                SourceCode.QUOTE,
                SUBJECT_ID,
                Map.of(
                        "changePct", new BigDecimal(changePct),
                        "price", new BigDecimal(price)),
                "test",
                Instant.now());
    }

    @Test
    void detectForItem_changePctAboveThreshold_insertsAndPublishesEvent() {
        // Arrange：涨跌幅 5.00% > 阈值 3.00%
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("5.00", "1680.50"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(SUBJECT_ID), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(false);

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert：入库 + 发事件，且记录字段正确
        ArgumentCaptor<AnomalyRecord> recordCaptor = ArgumentCaptor.forClass(AnomalyRecord.class);
        verify(anomalyRepository).save(recordCaptor.capture());
        AnomalyRecord saved = recordCaptor.getValue();
        assertThat(saved.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(saved.getAnomalyType()).isEqualTo(AnomalyType.PRICE_CHANGE);
        assertThat(saved.getChangePct()).contains(new BigDecimal("5.00"));
        assertThat(saved.getCurrentPrice()).contains(new BigDecimal("1680.50"));
        assertThat(saved.getTriggerTime()).isEqualTo(TRIGGER_TIME);
        assertThat(saved.isPushed()).isFalse();

        ArgumentCaptor<AnomalyDetectedEvent> eventCaptor =
                ArgumentCaptor.forClass(AnomalyDetectedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AnomalyDetectedEvent event = eventCaptor.getValue();
        assertThat(event.getSubjectId()).isEqualTo(SUBJECT_ID);
        assertThat(event.getAnomalyType()).isEqualTo(AnomalyType.PRICE_CHANGE);
        assertThat(event.getChangePct()).contains(new BigDecimal("5.00"));
        assertThat(event.getTriggerTime()).isEqualTo(TRIGGER_TIME);
    }

    @Test
    void detectForItem_changePctBelowThreshold_noInsertNoEvent() {
        // Arrange：涨跌幅 1.20% < 阈值 3.00
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("1.20", "1680.50"));

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert：未达阈值，不查重、不入库、不发事件
        verify(anomalyRepository, never()).existsByBusinessKey(any(), any(), any());
        verify(anomalyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detectForItem_changePctEqualsThreshold_triggers() {
        // Arrange：涨跌幅 = 阈值 3.00（边界，>= 触发，对齐 04 测试矩阵）
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("3.00", "1680.00"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(SUBJECT_ID), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(false);

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert
        verify(anomalyRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(AnomalyDetectedEvent.class));
    }

    @Test
    void detectForItem_negativeChangePctAboveThreshold_triggersOnAbsValue() {
        // Arrange：跌 -4.50%，绝对值 4.50 >= 3.00 → 触发（涨/跌均报异动）
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("-4.50", "1600.00"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(SUBJECT_ID), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(false);

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert
        verify(anomalyRepository).save(any());
        verify(eventPublisher).publishEvent(any(AnomalyDetectedEvent.class));
    }

    @Test
    void detectForItem_sameDayAlreadyExists_dedupSkips() {
        // Arrange：同日已记录（existsByBusinessKey=true）→ 去重跳过
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("5.00", "1680.50"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(SUBJECT_ID), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(true);

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert：查重命中，不入库、不发事件
        verify(anomalyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detectForItem_quoteMissing_skipsWithoutAnomaly() {
        // Arrange：行情 MISSING（盘外/停牌）→ 不报异动
        when(quoteAdapter.fetch(subject))
                .thenReturn(SourceResult.missing(SourceCode.QUOTE, SUBJECT_ID, "test"));

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert
        verify(anomalyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detectForItem_quoteFailed_skipsWithoutAnomaly() {
        // Arrange：行情 FAILED（取数异常降级）→ 不报异动
        when(quoteAdapter.fetch(subject))
                .thenReturn(SourceResult.failed(SourceCode.QUOTE, SUBJECT_ID, "test"));

        // Act
        job.detectForItem(itemWithThreshold(new BigDecimal("3.00")));

        // Assert
        verify(anomalyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detectForItem_subjectNotFound_skipsBeforeFetch() {
        // Arrange：标的不存在 → 跳过，不调行情
        long missingSubject = 9999L;
        WatchlistItem item = WatchlistItem.create(1L, missingSubject, new BigDecimal("3.00"));
        when(subjectRepository.findById(missingSubject)).thenReturn(Optional.empty());

        // Act
        job.detectForItem(item);

        // Assert：未调行情 adapter（标的不存在直接跳过）
        verify(quoteAdapter, never()).fetch(any());
        verify(anomalyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detect_quoteFetchThrows_doesNotBlockOtherItems() {
        // Arrange：两个 item，第一个 adapter.fetch 抛异常，第二个正常触发；detect() 整轮不抛、不阻断
        WatchlistItem item1 = WatchlistItem.create(1L, SUBJECT_ID, new BigDecimal("3.00"));
        WatchlistItem item2 = WatchlistItem.create(2L, 600036L, new BigDecimal("3.00"));
        Subject subject2 =
                Subject.reconstruct(
                        600036L,
                        SubjectCode.of("SH600036"),
                        Market.A_SHARE,
                        SubjectType.STOCK,
                        "招商银行",
                        Map.of(),
                        "银行",
                        SubjectStatus.ENABLED,
                        0L,
                        null,
                        null);
        when(subjectRepository.findById(SUBJECT_ID)).thenReturn(Optional.of(subject));
        when(subjectRepository.findById(600036L)).thenReturn(Optional.of(subject2));
        when(quoteAdapter.fetch(subject))
                .thenThrow(new RuntimeException("行情源超时")); // item1 fetch 抛异常
        when(quoteAdapter.fetch(subject2)).thenReturn(quoteOk("4.00", "35.00"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(600036L), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(false);
        when(watchlistRepository.findAllActiveItems()).thenReturn(List.of(item1, item2));

        // Act：整轮不应抛
        job.detect();

        // Assert：item1 异常被吞、不阻断；item2 正常入库 + 发事件
        verify(anomalyRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(AnomalyDetectedEvent.class));
    }

    @Test
    void detect_noActiveItems_doesNothing() {
        // Arrange：无活跃清单项
        when(watchlistRepository.findAllActiveItems()).thenReturn(List.of());

        // Act
        job.detect();

        // Assert：整轮空转，不调行情/仓储/事件
        verify(quoteAdapter, never()).fetch(any());
        verifyNoInteractions(anomalyRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void detectForItem_nullThresholdUsesDefault_stillTriggers() {
        // Arrange：清单项阈值为 null（防御性：用 DEFAULT_THRESHOLD=3.00）；涨跌幅 3.50 >= 3.00 触发
        when(quoteAdapter.fetch(subject)).thenReturn(quoteOk("3.50", "1680.00"));
        when(anomalyRepository.existsByBusinessKey(
                        eq(SUBJECT_ID), eq(AnomalyType.PRICE_CHANGE), eq(TRIGGER_DATE)))
                .thenReturn(false);
        WatchlistItem item = WatchlistItem.create(1L, SUBJECT_ID, null);

        // Act
        job.detectForItem(item);

        // Assert
        verify(anomalyRepository).save(any());
        verify(eventPublisher).publishEvent(any(AnomalyDetectedEvent.class));
    }
}
