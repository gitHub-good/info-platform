package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.aggregation.SubjectSyncConfigValidator;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.SourceStatus;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.NoopCircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import com.info.platform.infrastructure.common.SourceCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 备选源开关热切换单测（ADR-0032 语义保证 ③ 的行为层证据）： 开关从 @Value 启动期绑定改为运行时快照<b>用时读取</b>后， 同一 adapter
 * 实例在两次取数之间改配置即走新源（无重启、无重建 bean）。
 *
 * <p>覆盖三个消费点：QuoteSourceAdapter / ValuationSourceAdapter（{@code
 * datasource.{QUOTE,VALUATION}.params.backupSource}）与 RoutingSubjectListSource（{@code
 * subject.sync.aShareSource}）。配置中心以 Mockito stub 模拟「快照整体替换」——AtomicReference 换值即等价页面保存后的新快照。
 */
class BackupSourceHotSwitchTest {

    private ExecutorService exec;
    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;
    private NoopCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(new ObjectMapper());
        runner = new ResilienceRunner(exec);
        breaker = new NoopCircuitBreaker();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void quoteAdapter_switchTencentToEastmoney_takesEffectOnNextFetchWithoutRebuild() {
        AtomicReference<String> backupSource = new AtomicReference<>("tencent");
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote(anyString())).thenReturn(Optional.of(eastMoneyFields("1250.00")));
        when(tencent.fetchQuote(anyString()))
                .thenReturn(Optional.of(tencentStyleFields("1237.00")));
        // 装配后注入热读配置中心（@Autowired(required=false) 字段的测试注入点）
        QuoteSourceAdapter hotAdapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        hotAdapter.configCenter = quoteCenter(backupSource);

        Subject subject = subjectWithSecid("1.600519");

        // 第一次取数：backupSource=tencent → 强制腾讯单源（东财零调用）
        SourceResult first = hotAdapter.fetchFresh(subject);
        assertThat(first.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(first.getSource()).isEqualTo(QuoteSourceAdapter.TENCENT_ONLY_LABEL);
        verify(tencent).fetchQuote("sh600519");
        verify(east, never()).fetchQuote(anyString());

        // 页面保存等价动作：换快照值（同一 adapter 实例，无重启）
        backupSource.set("eastmoney");

        // 第二次取数：下次取数即用新源（东财路径）
        SourceResult second = hotAdapter.fetchFresh(subject);
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getSource()).isEqualTo("东方财富行情");
        verify(east).fetchQuote("1.600519");
    }

    @Test
    void valuationAdapter_switchEastmoneyToTencent_takesEffectOnNextFetchWithoutRebuild() {
        AtomicReference<String> backupSource = new AtomicReference<>("eastmoney");
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchValuation(anyString()))
                .thenReturn(Optional.of(Map.of("f57", "600519", "f162", "17.37", "f167", "6.15")));
        when(tencent.fetchQuote(anyString()))
                .thenReturn(Optional.of(Map.of("f57", "600519", "f162", "16.02", "f167", "3.07")));
        ValuationSourceAdapter adapter =
                new ValuationSourceAdapter(
                        cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = valuationCenter(backupSource);

        Subject subject = subjectWithSecid("1.600519");

        SourceResult first = adapter.fetchFresh(subject);
        assertThat(first.getStatus()).isEqualTo(SourceStatus.OK);
        verify(east).fetchValuation("1.600519");
        verify(tencent, never()).fetchQuote(anyString());

        backupSource.set("tencent");

        SourceResult second = adapter.fetchFresh(subject);
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getSource()).isEqualTo(ValuationSourceAdapter.TENCENT_ONLY_LABEL);
        verify(tencent).fetchQuote("sh600519");
    }

    @Test
    void quoteAdapter_corruptBackupSourceValue_warnsAndFallsBackToAuto() {
        AtomicReference<String> backupSource = new AtomicReference<>("not-a-mode");
        EastMoneyClient east = mock(EastMoneyClient.class);
        when(east.fetchQuote(anyString())).thenReturn(Optional.of(eastMoneyFields("1250.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        east,
                        mock(TencentQuoteClient.class),
                        "auto");
        adapter.configCenter = quoteCenter(backupSource);

        // DB 手改坏值不阻断取数：WARN 回落 auto（东财成功直用，不降级腾讯）
        SourceResult result = adapter.fetchFresh(subjectWithSecid("1.600519"));
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情");
    }

    @Test
    void subjectListSource_switchAutoToSina_takesEffectOnNextFetchWithoutRebuild() {
        AtomicReference<String> aShareSource = new AtomicReference<>("auto");
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        RoutingSubjectListSource source =
                new RoutingSubjectListSource(
                        eastMoney, sina, "auto", subjectSyncCenter(aShareSource));

        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("SH600519", "贵州茅台")));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenReturn(List.of(snapshot("SH600519", "贵州茅台(新浪)")));

        // auto：东财成功直用
        List<SubjectSnapshot> first = source.fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(first.get(0).name()).isEqualTo("贵州茅台");
        verify(eastMoney).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        verify(sina, never()).fetchAll(any());

        // 页面保存等价动作：aShareSource=sina（同一实例，无重启）
        aShareSource.set("sina");

        List<SubjectSnapshot> second = source.fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        assertThat(second.get(0).name()).isEqualTo("贵州茅台(新浪)");
        verify(sina).fetchAll(MarketSyncSpec.A_SHARE_STOCK);
        verify(eastMoney).fetchAll(MarketSyncSpec.A_SHARE_STOCK); // 仅首轮一次
    }

    // ---- fixtures ----

    /** 行情源配置中心 stub：dataSource(QUOTE) 按 {@code backupSource} 当前值现算视图（换值=换快照）。 */
    private static ConfigCenter quoteCenter(AtomicReference<String> backupSource) {
        ConfigCenter center = mock(ConfigCenter.class);
        when(center.dataSource(SourceCode.QUOTE))
                .thenAnswer(
                        inv ->
                                new RuntimeDataSource(
                                        SourceCode.QUOTE,
                                        true,
                                        RuntimeDataSource.Mode.REAL,
                                        1500,
                                        0,
                                        5,
                                        10,
                                        Map.of("backupSource", backupSource.get())));
        return center;
    }

    /** 估值源配置中心 stub（同行情款）。 */
    private static ConfigCenter valuationCenter(AtomicReference<String> backupSource) {
        ConfigCenter center = mock(ConfigCenter.class);
        when(center.dataSource(SourceCode.VALUATION))
                .thenAnswer(
                        inv ->
                                new RuntimeDataSource(
                                        SourceCode.VALUATION,
                                        true,
                                        RuntimeDataSource.Mode.REAL,
                                        2000,
                                        0,
                                        3600,
                                        30,
                                        Map.of("backupSource", backupSource.get())));
        return center;
    }

    /** 标的池同步配置中心 stub：document("subject.sync") 按当前 aShareSource 值现算。 */
    private static ConfigCenter subjectSyncCenter(AtomicReference<String> aShareSource) {
        ObjectMapper mapper = new ObjectMapper();
        ConfigCenter center = mock(ConfigCenter.class);
        when(center.document(SubjectSyncConfigValidator.KEY))
                .thenAnswer(
                        inv ->
                                Optional.of(
                                        mapper.valueToTree(
                                                Map.of("aShareSource", aShareSource.get()))));
        return center;
    }

    private static Map<String, Object> eastMoneyFields(String price) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("f57", "600519");
        fields.put("f58", "贵州茅台");
        fields.put("f43", price);
        fields.put("f46", "1250.01");
        fields.put("f44", "1256.13");
        fields.put("f45", "1231.05");
        fields.put("f60", "1251.24");
        fields.put("f169", "-14.24");
        fields.put("f170", "-1.14");
        fields.put("f47", 31239);
        fields.put("f48", "3867310920");
        fields.put("f171", "2.00");
        fields.put("f168", "0.25");
        return fields;
    }

    /** 腾讯路径产出东财 f 键中间结构（数值口径，TencentQuoteClient 契约）。 */
    private static Map<String, Object> tencentStyleFields(String price) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("f57", "600519");
        fields.put("f58", "贵州茅台");
        fields.put("f43", new BigDecimal(price));
        fields.put("f46", new BigDecimal("1250.01"));
        fields.put("f44", new BigDecimal("1256.13"));
        fields.put("f45", new BigDecimal("1231.05"));
        fields.put("f60", new BigDecimal("1251.24"));
        fields.put("f169", new BigDecimal("-14.24"));
        fields.put("f170", new BigDecimal("-1.14"));
        fields.put("f47", 31239L);
        fields.put("f48", new BigDecimal("3867310920"));
        fields.put("f171", new BigDecimal("2.00"));
        fields.put("f168", new BigDecimal("0.25"));
        return fields;
    }

    private static SubjectSnapshot snapshot(String code, String name) {
        return new SubjectSnapshot(code, name, null, "1.600519", MarketSyncSpec.A_SHARE_STOCK);
    }

    private static Subject subjectWithSecid(String secid) {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("eastmoney", secid, "tushare", "600519.SH"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
