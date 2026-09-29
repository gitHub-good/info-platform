package com.info.platform.application.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.HKUSMarketSnapshotService.SnapshotReport;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository;
import com.info.platform.domain.mainline.IndustryMarketSnapshotRepository.MarketSnapshotRow;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository;
import com.info.platform.domain.valuation.MarketDailySnapshotRepository.MarketDailyRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * HKUSMarketSnapshotService 单测（M29 T252，方案 §4 C10 / ADR-0064 裁决 1/3/4）：双源全 Mock 零外呼。
 *
 * <p>覆盖：主路径快照落行（market_cap/currency 原币）+ 行业市值加权聚合（CAP_WEIGHTED 数学复算）+ 覆盖 &lt;80% 回退等权 （EQUAL 留痕）+
 * 美股首轮宽取收敛（阈值分桶升/降级）+ 次轮不再收敛 + 主链整轮失败切备链（source 留痕）+ 双链全败 no-op + 港股无收敛 + UNKNOWN 行业不进聚合行。AAA 结构。
 */
class HKUSMarketSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    private HkusQuoteSource primary;
    private HkusQuoteSource backup;
    private SubjectRepository subjectRepository;
    private MarketDailySnapshotRepository dailyRepository;
    private IndustryMarketSnapshotRepository industryRepository;
    private RuntimeConfigService runtimeConfigService;
    private HKUSMarketSnapshotService service;

    @BeforeEach
    void setUp() {
        primary = mock(HkusQuoteSource.class);
        backup = mock(HkusQuoteSource.class);
        when(primary.sourceCode()).thenReturn("tencent");
        when(backup.sourceCode()).thenReturn("sina");
        subjectRepository = mock(SubjectRepository.class);
        dailyRepository = mock(MarketDailySnapshotRepository.class);
        industryRepository = mock(IndustryMarketSnapshotRepository.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.read(anyString())).thenReturn(Optional.empty());
        when(dailyRepository.upsertAll(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        when(industryRepository.replaceIndustryRows(anyString(), anyString(), anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());
        when(subjectRepository.updateStatusForUsCodes(anyList(), Mockito.anyBoolean()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        service =
                new HKUSMarketSnapshotService(
                        primary,
                        backup,
                        subjectRepository,
                        dailyRepository,
                        industryRepository,
                        runtimeConfigService,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Subject subject(
            long id,
            String code,
            Market market,
            String name,
            String industry,
            SubjectStatus status) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                market,
                SubjectType.STOCK,
                name,
                null,
                industry,
                status,
                0,
                null,
                null);
    }

    /** f 键中间结构构造（market_cap 亿原币 + f43/f170/f30）。 */
    private static Map<String, Object> fields(String price, String pct, String capYi, String f30) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (price != null) {
            f.put("f43", new BigDecimal(price));
        }
        if (pct != null) {
            f.put("f170", new BigDecimal(pct));
        }
        if (capYi != null) {
            f.put("market_cap", new BigDecimal(capYi));
        }
        if (f30 != null) {
            f.put("f30", f30);
        }
        return f;
    }

    private void stubPools(List<Subject> hk, List<Subject> us) {
        when(subjectRepository.loadBucket(Market.HK, SubjectType.STOCK)).thenReturn(hk);
        when(subjectRepository.loadBucket(Market.US, SubjectType.STOCK)).thenReturn(us);
    }

    @Test
    void run_happyPath_snapshotsAggregatesAndConverges() {
        // Arrange：港 2 只（同行业，市值加权）+ 美 3 只（1 大市值 / 1 小市值 / 1 停用宽取行），主链全量返回
        Subject tencent = subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        Subject kingsoft = subject(2, "HK03888", Market.HK, "金山软件", "软件服务", SubjectStatus.ENABLED);
        Subject unknownHk =
                subject(3, "HK00005", Market.HK, "汇丰控股", "UNKNOWN", SubjectStatus.ENABLED);
        Subject apple = subject(11, "USAAPL", Market.US, "苹果", "电子设备与元件", SubjectStatus.ENABLED);
        Subject small = subject(12, "USSMALL", Market.US, "小市值公司", "制药", SubjectStatus.ENABLED);
        Subject demoted = subject(13, "USOLD", Market.US, "已停用股", "汽车", SubjectStatus.DISABLED);
        stubPools(List.of(tencent, kingsoft, unknownHk), List.of(apple, small, demoted));
        Map<String, Map<String, Object>> hkRows =
                Map.of(
                        "HK00700", fields("432.000", "-0.59", "39873.0635", "2026/09/29 16:08:09"),
                        "HK03888", fields("30.500", "1.41", "100.00", "2026/09/29 16:08:08"),
                        "HK00005", fields("70.000", "0.50", "12000.00", "2026/09/29 16:08:07"));
        Map<String, Map<String, Object>> usRows =
                Map.of(
                        "USAAPL", fields("338.40", "-0.78", "49356.00844", "2026-09-28 16:00:01"),
                        "USSMALL", fields("5.00", "2.00", "15.00", "2026-09-28 16:00:01"),
                        "USOLD", fields("1.00", "-1.00", "25.00", "2026-09-28 16:00:01"));
        when(primary.fetchBySubjectCodes(anyList())).thenReturn(hkRows).thenReturn(usRows);

        // Act
        SnapshotReport report = service.run();

        // Assert：快照行（3 HK + 3 US 全落——美股首轮宽取含停用行；HK 次轮起只取启用）
        assertThat(report.snapshotRows()).isEqualTo(6);
        ArgumentCaptor<List<MarketDailyRow>> dailyCaptor = ArgumentCaptor.forClass(List.class);
        verify(dailyRepository, times(2)).upsertAll(dailyCaptor.capture());
        List<MarketDailyRow> hkDaily = dailyCaptor.getAllValues().get(0);
        assertThat(hkDaily).hasSize(3);
        MarketDailyRow tencentRow = hkDaily.get(0);
        assertThat(tencentRow.subjectId()).isEqualTo(1L);
        assertThat(tencentRow.snapshotDate()).isEqualTo("2026-09-29");
        assertThat(tencentRow.source()).isEqualTo("tencent");
        assertThat(tencentRow.quoteTime()).isEqualTo("2026/09/29 16:08:09");
        assertThat(tencentRow.marketCap()).isEqualByComparingTo(39873.0635);
        assertThat(tencentRow.currency()).isEqualTo("HKD");
        List<MarketDailyRow> usDaily = dailyCaptor.getAllValues().get(1);
        assertThat(usDaily.get(0).currency()).isEqualTo("USD");
        assertThat(usDaily.get(0).marketCap()).isEqualByComparingTo(49356.00844);

        // 行业聚合：软件服务市值加权（覆盖 100% → CAP_WEIGHTED），UNKNOWN 不进行业行
        ArgumentCaptor<List<MarketSnapshotRow>> industryCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(industryRepository, times(2))
                .replaceIndustryRows(anyString(), anyString(), industryCaptor.capture());
        List<MarketSnapshotRow> hkIndustries = industryCaptor.getAllValues().get(0);
        assertThat(hkIndustries).hasSize(1); // 仅「软件服务」；UNKNOWN 成员剔除
        MarketSnapshotRow software = hkIndustries.get(0);
        assertThat(software.market()).isEqualTo("HK");
        assertThat(software.rowType()).isEqualTo("INDUSTRY");
        assertThat(software.currency()).isEqualTo("HKD");
        assertThat(software.source()).isEqualTo("hkus-aggregate");
        assertThat(software.aggMethod()).isEqualTo("CAP_WEIGHTED");
        // 加权复算：(39873.0635×-0.59 + 100×1.41) / 39973.0635 ≈ -0.5847
        assertThat(software.pctDay())
                .isCloseTo(-0.5847, org.assertj.core.data.Offset.offset(0.0005));
        assertThat(software.upCount()).isEqualTo(1);
        assertThat(software.downCount()).isEqualTo(1);
        assertThat(software.totalMv())
                .isCloseTo(39973.0635e8, org.assertj.core.data.Offset.offset(1.0));
        assertThat(software.mainNetFlow()).isNull(); // A 股通道专有，如实缺省
        assertThat(software.pctD5()).isNull(); // T255 读面承接

        // 美股收敛（首轮宽取）：≥ 20 亿 USD 保持（USAAPL/USOLD=25 亿），< 20 亿降级（USSMALL=15 亿）
        verify(subjectRepository).updateStatusForUsCodes(List.of("USAAPL", "USOLD"), true);
        verify(subjectRepository).updateStatusForUsCodes(List.of("USSMALL"), false);
        assertThat(report.demoted()).isEqualTo(1);
        verify(backup, never()).fetchBySubjectCodes(anyList()); // 主链健康不触备链
    }

    @Test
    void run_lowMvCoverage_fallsBackToEqualWeight() {
        // Arrange：行业 2 成员，大市值缺 pct（覆盖 10/110 < 80%）→ 等权
        Subject big = subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        Subject small = subject(2, "HK03888", Market.HK, "金山软件", "软件服务", SubjectStatus.ENABLED);
        stubPools(List.of(big, small), List.of());
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of(
                                "HK00700", fields("432.000", null, "100.00", "2026/09/29 16:08:09"),
                                "HK03888",
                                        fields("30.500", "5.00", "10.00", "2026/09/29 16:08:08")))
                .thenReturn(Map.of());

        service.run();

        ArgumentCaptor<List<MarketSnapshotRow>> industryCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(industryRepository)
                .replaceIndustryRows(anyString(), anyString(), industryCaptor.capture());
        MarketSnapshotRow row = industryCaptor.getValue().get(0);
        assertThat(row.aggMethod()).isEqualTo("EQUAL");
        assertThat(row.pctDay()).isEqualTo(5.0); // 唯一有 pct 成员等权即自身
        assertThat(row.totalMv()).isCloseTo(110.0e8, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void run_usConvergenceThreshold_configDriven() {
        // Arrange：runtime_config 阈值 100 亿 USD（usMvMinUsd）——cap 15 亿降级、60 亿保持
        when(runtimeConfigService.read("subject.sync.us-mv-min-usd"))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry(
                                        "subject.sync.us-mv-min-usd",
                                        "{\"usMvMinUsd\":1.0e10}",
                                        null,
                                        "测试",
                                        null)));
        Subject a = subject(11, "USAAPL", Market.US, "苹果", "电子设备与元件", SubjectStatus.ENABLED);
        Subject b = subject(12, "USSMALL", Market.US, "小市值公司", "制药", SubjectStatus.ENABLED);
        stubPools(List.of(), List.of(a, b));
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of(
                                "USAAPL",
                                        fields("338.40", "-0.78", "150.00", "2026-09-28 16:00:01"),
                                "USSMALL", fields("5.00", "2.00", "15.00", "2026-09-28 16:00:01")));

        service.run();

        // 阈值 100 亿 USD：150 亿保持 / 15 亿降级（配置驱动热生效）
        verify(subjectRepository).updateStatusForUsCodes(List.of("USAAPL"), true);
        verify(subjectRepository).updateStatusForUsCodes(List.of("USSMALL"), false);
    }

    @Test
    void run_secondRoundSameDay_skipsUsConvergenceAndDisabledSubjects() {
        // Arrange：两轮同日——首轮宽取收敛 + 次轮仅启用行不再收敛（升降级每日一轮）
        Subject enabled = subject(11, "USAAPL", Market.US, "苹果", "电子设备与元件", SubjectStatus.ENABLED);
        Subject disabled = subject(12, "USSMALL", Market.US, "小市值公司", "制药", SubjectStatus.DISABLED);
        stubPools(List.of(), List.of(enabled, disabled));
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of(
                                "USAAPL",
                                fields("338.40", "-0.78", "49356.00", null),
                                "USSMALL",
                                fields("5.00", "2.00", "15.00", null)))
                .thenReturn(Map.of("USAAPL", fields("338.40", "-0.78", "49356.00", null)));

        service.run();
        service.run(); // 同日次轮

        // 首轮宽取收敛一轮（keep + demote 各一次）；次轮不重复（times 仍为 1）
        verify(subjectRepository, times(1)).updateStatusForUsCodes(List.of("USAAPL"), true);
        verify(subjectRepository, times(1)).updateStatusForUsCodes(List.of("USSMALL"), false);
        ArgumentCaptor<List<String>> usPoolCaptor = ArgumentCaptor.forClass(List.class);
        verify(primary, times(2)).fetchBySubjectCodes(usPoolCaptor.capture());
        assertThat(usPoolCaptor.getAllValues().get(0)).containsExactly("USAAPL", "USSMALL"); // 首轮宽取
        assertThat(usPoolCaptor.getAllValues().get(1)).containsExactly("USAAPL"); // 次轮仅启用行
    }

    @Test
    void run_primaryFails_roundDegradesToSinaWithSourceLabel() {
        // Arrange：主链异常 → 整轮切新浪（轮级降级非逐标的），source 落行留痕
        Subject tencent = subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        stubPools(List.of(tencent), List.of());
        when(primary.fetchBySubjectCodes(anyList()))
                .thenThrow(new RuntimeException("qt.gtimg.cn 502"));
        when(backup.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of("HK00700", fields("432.000", "-1.77", null, "2026/09/29 16:08:08")))
                .thenReturn(Map.of());

        SnapshotReport report = service.run();

        assertThat(report.snapshotRows()).isEqualTo(1);
        ArgumentCaptor<List<MarketDailyRow>> dailyCaptor = ArgumentCaptor.forClass(List.class);
        verify(dailyRepository).upsertAll(dailyCaptor.capture());
        assertThat(dailyCaptor.getValue().get(0).source()).isEqualTo("sina");
        assertThat(dailyCaptor.getValue().get(0).marketCap()).isNull(); // 新浪 rt_hk 无市值位如实缺省
        assertThat(report.detail()).contains("src=sina");
        verify(backup, times(1)).fetchBySubjectCodes(anyList()); // HK 备链（US 空池零请求）
    }

    @Test
    void run_primaryEmptyBody_treatedAsRoundFailureSwitchesBackup() {
        // Arrange：主链 200 空体（非空池零行）→ 视同整轮失败切备链（封禁实测签名防御）
        Subject tencent = subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        stubPools(List.of(tencent), List.of());
        when(primary.fetchBySubjectCodes(anyList())).thenReturn(Map.of());
        when(backup.fetchBySubjectCodes(anyList())).thenReturn(Map.of()).thenReturn(Map.of());

        SnapshotReport report = service.run();

        assertThat(report.snapshotRows()).isZero(); // 双链零行 no-op，不动旧快照
        verify(backup, times(1)).fetchBySubjectCodes(anyList());
        verify(dailyRepository, never()).upsertAll(anyList());
    }

    @Test
    void run_dualChainFailure_noOpWithoutTouchingOldSnapshot() {
        // Arrange：双链全败（异常）→ 本轮 no-op：不写快照、不收敛
        Subject tencent = subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        stubPools(List.of(tencent), List.of());
        when(primary.fetchBySubjectCodes(anyList())).thenThrow(new RuntimeException("timeout"));
        when(backup.fetchBySubjectCodes(anyList())).thenThrow(new RuntimeException("403"));

        SnapshotReport report = service.run();

        assertThat(report.snapshotRows()).isZero();
        verify(dailyRepository, never()).upsertAll(anyList());
        verify(industryRepository, never())
                .replaceIndustryRows(anyString(), anyString(), anyList());
        verify(subjectRepository, never()).updateStatusForUsCodes(anyList(), Mockito.anyBoolean());
    }

    // ---- M29 P2-01 回归：subject.industry 被 F10 回填改写后，行业聚合走「替换写」清旧行 ----

    @Test
    void run_industryRewritten_staleIndustryRowPrunedSameDay() {
        // Arrange：首轮 00700=互联网 + 00005=银行 → 聚合 {互联网, 银行}
        Subject tencentOld =
                subject(1, "HK00700", Market.HK, "腾讯控股", "互联网", SubjectStatus.ENABLED);
        Subject hsbc = subject(2, "HK00005", Market.HK, "汇丰控股", "银行", SubjectStatus.ENABLED);
        stubPools(List.of(tencentOld, hsbc), List.of());
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of(
                                "HK00700", fields("432.000", "-1.77", "39873.00", "2026/09/29 12:12:19"),
                                "HK00005", fields("70.000", "0.50", "12000.00", "2026/09/29 12:12:19")))
                .thenReturn(Map.of());

        service.run();

        // Act：F10 行业回填改写 00700 互联网→软件服务（复现 P2-01 场景）后次轮聚合 → {软件服务, 银行}
        Subject tencentRewritten =
                subject(1, "HK00700", Market.HK, "腾讯控股", "软件服务", SubjectStatus.ENABLED);
        stubPools(List.of(tencentRewritten, hsbc), List.of());
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(
                        Map.of(
                                "HK00700", fields("432.000", "-0.59", "39873.00", "2026/09/29 16:08:09"),
                                "HK00005", fields("70.000", "0.60", "12000.00", "2026/09/29 16:08:08")));

        service.run();

        // Assert：次轮 replaceIndustryRows 携带 {银行, 软件服务}（当日口径）——「互联网」不在保留集，由仓储同事务清理（孤儿行不残留）
        ArgumentCaptor<List<MarketSnapshotRow>> industryCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(industryRepository, times(2))
                .replaceIndustryRows(anyString(), anyString(), industryCaptor.capture());
        List<MarketSnapshotRow> firstRound = industryCaptor.getAllValues().get(0);
        List<MarketSnapshotRow> secondRound = industryCaptor.getAllValues().get(1);
        assertThat(firstRound)
                .extracting(MarketSnapshotRow::dimName)
                .containsExactlyInAnyOrder("互联网", "银行");
        assertThat(secondRound)
                .extracting(MarketSnapshotRow::dimName)
                .containsExactlyInAnyOrder("软件服务", "银行"); // 旧行业不在本次集合 → 写侧 DELETE
        assertThat(secondRound).allSatisfy(row -> assertThat(row.market()).isEqualTo("HK"));

        // Assert：港美股行业聚合不再走裸 UPSERT（只增不清 = P2-01 根因）
        verify(industryRepository, never()).upsertAll(anyList());
    }

    @Test
    void run_hkNeverConverges_andEmptyPoolSkipsMarket() {
        // Arrange：港股全量无市值收敛（方案 §1.1 裁决③）；空市场池跳过
        stubPools(List.of(), List.of());
        when(primary.fetchBySubjectCodes(anyList())).thenReturn(Map.of());

        SnapshotReport report = service.run();

        verify(dailyRepository, never()).upsertAll(anyList());
        assertThat(report.detail()).contains("src=none");
    }

    @Test
    void run_usDisabledSubjectWithoutQuoteRow_notConverged() {
        // Arrange：宽取行响应缺席（无行情行）——不传收敛（避免误杀/误复活）
        Subject apple = subject(11, "USAAPL", Market.US, "苹果", "电子设备与元件", SubjectStatus.ENABLED);
        Subject missing = subject(12, "USGONE", Market.US, "无行情股", "汽车", SubjectStatus.DISABLED);
        stubPools(List.of(), List.of(apple, missing));
        when(primary.fetchBySubjectCodes(anyList()))
                .thenReturn(Map.of("USAAPL", fields("338.40", "-0.78", "49356.00", null)))
                .thenReturn(Map.of());

        service.run();

        verify(subjectRepository).updateStatusForUsCodes(List.of("USAAPL"), true);
        verify(subjectRepository, never())
                .updateStatusForUsCodes(Mockito.eq(List.of("USGONE")), Mockito.anyBoolean());
    }
}
