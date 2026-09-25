package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.domain.feed.SourceDailyStats;
import com.info.platform.domain.feed.SourceDailyStatsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SourceStaleCheckService 单测（T128，方案 §4.7 / REQ AMB-01）：标记（窗内零净入库 + 源龄满窗）/ 恢复自动解除（净入库 &gt; 0 清 标记）/
 * 月频源 35 天长窗（7 天窗不误报）/ 新建源不满窗不标记 / 已标记不重复写 / 停用源不检查。mock 仓储 + 固定时钟。AAA 结构。
 */
class SourceStaleCheckServiceTest {

    /** 检查时点：上海 2026-09-23 04:10（= UTC 2026-09-22 20:10）。 */
    private static final Instant NOW = Instant.parse("2026-09-22T20:10:00Z");

    private static final String TODAY = "2026-09-23";

    private InfoSourceRepository infoSourceRepository;
    private SourceDailyStatsRepository statsRepository;
    private SourceStaleCheckService service;

    @BeforeEach
    void setUp() {
        infoSourceRepository = mock(InfoSourceRepository.class);
        statsRepository = mock(SourceDailyStatsRepository.class);
        // 最长窗（35 天）一次取数，服务内按各源窗过滤
        when(statsRepository.findSince("2026-08-19")).thenReturn(List.of());
        service =
                new SourceStaleCheckService(
                        infoSourceRepository, statsRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InfoSource source(
            long id, String code, boolean enabled, Instant createdAt, String staleSince) {
        return InfoSource.reconstruct(
                id,
                code,
                code,
                "快讯",
                AdapterType.RSS,
                null,
                "https://example.com/" + code,
                SourceConfig.empty().withStaleSince(staleSince),
                30,
                enabled,
                false,
                false,
                createdAt,
                createdAt);
    }

    private static SourceDailyStats stat(long sourceId, String statDate, long newCount) {
        return new SourceDailyStats(null, sourceId, statDate, 5, 0, newCount, 0, NOW, NOW);
    }

    /** 近 7 天逐日零净入库的统计行（stat_date 升序）。 */
    private static List<SourceDailyStats> zeroWeek(long sourceId) {
        List<SourceDailyStats> rows = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {
            rows.add(stat(sourceId, minusDays(TODAY, i), 0));
        }
        return rows;
    }

    private static String minusDays(String date, int days) {
        return java.time.LocalDate.parse(date).minusDays(days).toString();
    }

    @Test
    void checkAll_zeroNetIntakeFullAge_marksStaleSinceToday() {
        // Arrange：老源（30 天前建）近 7 天零净入库
        InfoSource stale =
                source(1, "stale_a", true, NOW.minus(java.time.Duration.ofDays(30)), null);
        when(infoSourceRepository.findActive()).thenReturn(List.of(stale));
        when(statsRepository.findSince("2026-08-19")).thenReturn(zeroWeek(1));

        // Act
        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        // Assert：config.staleSince 写入检查日，save 落库
        ArgumentCaptor<InfoSource> captor = ArgumentCaptor.forClass(InfoSource.class);
        verify(infoSourceRepository).save(captor.capture());
        assertThat(captor.getValue().getConfig().staleSince()).isEqualTo(TODAY);
        assertThat(report.checked()).isEqualTo(1);
        assertThat(report.marked()).isEqualTo(1);
        assertThat(report.detail()).contains("stale_a");
    }

    @Test
    void checkAll_alreadyMarked_notRewritten() {
        // Arrange：已标记（昨日写入）且持续零净入库 → 不重复写（幂等，staleSince 保留首标日）
        InfoSource marked =
                source(2, "marked_b", true, NOW.minus(java.time.Duration.ofDays(30)), "2026-09-20");
        when(infoSourceRepository.findActive()).thenReturn(List.of(marked));
        when(statsRepository.findSince("2026-08-19")).thenReturn(zeroWeek(2));

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        verify(infoSourceRepository, never()).save(any());
        assertThat(report.marked()).isZero();
        assertThat(report.checked()).isEqualTo(1);
    }

    @Test
    void checkAll_recoveredIntake_clearsMark() {
        // Arrange：已标记源窗内出现净入库 > 0（恢复）→ 清除标记
        InfoSource marked =
                source(
                        3,
                        "recovered_c",
                        true,
                        NOW.minus(java.time.Duration.ofDays(30)),
                        "2026-09-15");
        when(infoSourceRepository.findActive()).thenReturn(List.of(marked));
        List<SourceDailyStats> rows = new ArrayList<>(zeroWeek(3));
        rows.add(stat(3, minusDays(TODAY, 2), 4)); // 前天净入库 4 条
        when(statsRepository.findSince("2026-08-19")).thenReturn(rows);

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        ArgumentCaptor<InfoSource> captor = ArgumentCaptor.forClass(InfoSource.class);
        verify(infoSourceRepository).save(captor.capture());
        assertThat(captor.getValue().getConfig().staleSince()).isNull();
        assertThat(report.cleared()).isEqualTo(1);
    }

    @Test
    void checkAll_monthlySource_zeroIntakeIn35Days_marksDespite7DaySilence() {
        // Arrange：月频源（em_macro_indicators）近 7 天零净入库且 35 天窗内亦零 → 标记（35 天窗语义，ADR-0049 裁量 7）
        InfoSource monthly =
                source(
                        4,
                        "em_macro_indicators",
                        true,
                        NOW.minus(java.time.Duration.ofDays(60)),
                        null);
        when(infoSourceRepository.findActive()).thenReturn(List.of(monthly));
        List<SourceDailyStats> rows = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {
            rows.add(stat(4, minusDays(TODAY, i), 0)); // 近 7 天零
        }
        when(statsRepository.findSince("2026-08-19")).thenReturn(rows);

        // Act：近 7 天零 + 35 天窗内无入库 → 标记
        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        ArgumentCaptor<InfoSource> captor = ArgumentCaptor.forClass(InfoSource.class);
        verify(infoSourceRepository).save(captor.capture());
        assertThat(captor.getValue().getConfig().staleSince()).isEqualTo(TODAY);
        assertThat(report.marked()).isEqualTo(1);
    }

    @Test
    void checkAll_monthlySource_intake10DaysAgo_notMarked_7DayWindowWouldMisfire() {
        // Arrange：月频源 10 天前有净入库（7 天窗外、35 天窗内）→ 月频 35 天窗判活跃不标记（7 天窗会误标）
        InfoSource monthly =
                source(
                        5,
                        "em_macro_indicators",
                        true,
                        NOW.minus(java.time.Duration.ofDays(60)),
                        null);
        when(infoSourceRepository.findActive()).thenReturn(List.of(monthly));
        List<SourceDailyStats> rows = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {
            rows.add(stat(5, minusDays(TODAY, i), 0));
        }
        rows.add(stat(5, minusDays(TODAY, 10), 2)); // 10 天前净入库（35 天窗内 → 活跃）
        when(statsRepository.findSince("2026-08-19")).thenReturn(rows);

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        verify(infoSourceRepository, never()).save(any());
        assertThat(report.marked()).isZero();
    }

    @Test
    void checkAll_youngSourceUnderWindow_notMarked() {
        // Arrange：3 天前新建的源零净入库——源龄不满 7 天窗，不标记（避免「上线即停更」误报）
        InfoSource young =
                source(6, "young_d", true, NOW.minus(java.time.Duration.ofDays(3)), null);
        when(infoSourceRepository.findActive()).thenReturn(List.of(young));
        List<SourceDailyStats> rows = new ArrayList<>();
        for (int i = 3; i >= 1; i--) {
            rows.add(stat(6, minusDays(TODAY, i), 0));
        }
        when(statsRepository.findSince("2026-08-19")).thenReturn(rows);

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        verify(infoSourceRepository, never()).save(any());
        assertThat(report.marked()).isZero();
    }

    @Test
    void checkAll_disabledSource_notChecked() {
        // Arrange：停用源不进检查（findActive 只回启用源——仓库语义）；软删源亦然
        when(infoSourceRepository.findActive()).thenReturn(List.of());

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        verify(infoSourceRepository, never()).save(any());
        assertThat(report.checked()).isZero();
        assertThat(report.detail()).isNotNull();
    }

    @Test
    void checkActive_intakeWithinWindow_keepsClean_unmarked() {
        // Arrange：正常源（窗内有净入库）未标记 → 不写库（无副作用）
        InfoSource healthy =
                source(7, "healthy_e", true, NOW.minus(java.time.Duration.ofDays(30)), null);
        when(infoSourceRepository.findActive()).thenReturn(List.of(healthy));
        List<SourceDailyStats> rows = new ArrayList<>(zeroWeek(7));
        rows.add(stat(7, minusDays(TODAY, 1), 12));
        when(statsRepository.findSince("2026-08-19")).thenReturn(rows);

        SourceStaleCheckService.StaleCheckReport report = service.checkAll();

        verify(infoSourceRepository, never()).save(any());
        assertThat(report.marked()).isZero();
        assertThat(report.cleared()).isZero();
    }
}
