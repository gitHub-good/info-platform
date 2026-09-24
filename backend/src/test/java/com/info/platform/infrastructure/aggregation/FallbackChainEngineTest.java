package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
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
import java.util.LinkedHashMap;
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
 * 降级链引擎行为单测（ADR-0033）：三个消费点（QuoteSourceAdapter / ValuationSourceAdapter /
 * RoutingSubjectListSource）按 {@code fallbackChain} 顺序取数——主源成功零备选外呼 / 主源失败备选接管（来源标注带 「→X备选」）/
 * 全链失败沿既有弹性语义降级 MISSING / 空链仅主源 / 换主源（链序自定义）/ DB 坏链 WARN 回落全链 / 页面保存等价动作（换快照）下一次取数即新链（热生效）。
 */
class FallbackChainEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExecutorService exec;
    private SourceCache cache;
    private FieldMapper fieldMapper;
    private ResilienceRunner runner;
    private NoopCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        cache = new SourceCache();
        fieldMapper = new FieldMapper(JSON);
        runner = new ResilienceRunner(exec);
        breaker = new NoopCircuitBreaker();
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    // —— 行情源 ——

    @Test
    void quote_primarySucceeds_backupNeverCalled_labeledPrimary() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenReturn(Optional.of(eastFields("1250.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of("eastmoney", "tencent")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情");
        verify(tencent, never()).fetchQuote(anyString());
    }

    @Test
    void quote_primaryFails_backupTakesOver_withFallbackLabel() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenThrow(new IllegalStateException("push2 封禁"));
        when(tencent.fetchQuote("sh600519")).thenReturn(Optional.of(tencentFields("1237.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of("eastmoney", "tencent")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        // RawFetch.source 标注实际命中 provider：健康徽章可区分兜底轮
        assertThat(result.getSource()).isEqualTo("东方财富行情→腾讯备选");
        assertThat((BigDecimal) result.getData().get("price")).isEqualByComparingTo("1237.00");
    }

    @Test
    void quote_fullChainFails_degradesToMissingByResilience() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenThrow(new IllegalStateException("东财 500"));
        when(tencent.fetchQuote("sh600519")).thenThrow(new IllegalStateException("腾讯 500"));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of("eastmoney", "tencent")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        // 全链失败走既有弹性降级：MISSING（不阻断聚合其他分区）
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void quote_reversedChain_tencentPrimary_eastBackup() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(tencent.fetchQuote("sh600519")).thenThrow(new IllegalStateException("腾讯限频"));
        when(east.fetchQuote("1.600519")).thenReturn(Optional.of(eastFields("1250.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of("tencent", "eastmoney")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        // 换主源：腾讯主源失败 → 东财备选接管，标注「腾讯行情→东方财富备选」
        assertThat(result.getSource()).isEqualTo("腾讯行情→东方财富备选");
    }

    @Test
    void quote_emptyChain_primaryOnly_noFallbackAttempt() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenThrow(new IllegalStateException("东财 500"));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        // 页面清空备选（仅主源）：空链 → 主源失败即降级，零备选外呼
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of()));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        verify(tencent, never()).fetchQuote(anyString());
    }

    @Test
    void quote_chainHotSwitch_takesEffectOnNextFetch() {
        AtomicReference<List<String>> chain = new AtomicReference<>(List.of("eastmoney"));
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenThrow(new IllegalStateException("东财 500"));
        when(tencent.fetchQuote("sh600519")).thenReturn(Optional.of(tencentFields("1237.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter = quoteCenter(chain);

        assertThat(adapter.fetchFresh(aShareSubject()).getStatus()).isEqualTo(SourceStatus.MISSING);

        // 页面保存等价动作：换快照链（同一 adapter 实例，无重启）
        chain.set(List.of("eastmoney", "tencent"));

        SourceResult second = adapter.fetchFresh(aShareSubject());
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getSource()).isEqualTo("东方财富行情→腾讯备选");
    }

    @Test
    void quote_corruptChainInDb_warnsAndFallsBackToFullChain() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchQuote("1.600519")).thenReturn(Optional.of(eastFields("1250.00")));
        QuoteSourceAdapter adapter =
                new QuoteSourceAdapter(cache, fieldMapper, runner, breaker, east, tencent, "auto");
        // DB 手改坏链（sina 不在行情源注册表）：WARN 回落全链（东财主源直用）
        adapter.configCenter = quoteCenter(new AtomicReference<>(List.of("sina", "eastmoney")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富行情");
        verify(tencent, never()).fetchQuote(anyString());
    }

    // —— 估值源 ——

    @Test
    void valuation_primaryFails_backupTakesOver_withValuationFallbackLabel() {
        EastMoneyClient east = mock(EastMoneyClient.class);
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(east.fetchValuation("1.600519")).thenThrow(new IllegalStateException("东财 500"));
        when(tencent.fetchQuote("sh600519"))
                .thenReturn(
                        Optional.of(
                                Map.of(
                                        "f57", "600519",
                                        "f162", new BigDecimal("16.02"),
                                        "f167", new BigDecimal("3.07"))));
        ValuationSourceAdapter adapter =
                new ValuationSourceAdapter(
                        cache, fieldMapper, runner, breaker, east, tencent, "auto");
        adapter.configCenter =
                valuationCenter(new AtomicReference<>(List.of("eastmoney", "tencent")));

        SourceResult result = adapter.fetchFresh(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富估值→腾讯备选");
        assertThat((BigDecimal) result.getData().get("peTtm")).isEqualByComparingTo("16.02");
    }

    // —— A 股列表桶（subject.sync 链模型） ——

    @Test
    void subjectList_chainFromDoc_reversedOrder_sinaPrimary() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(List.of(snapshot("贵州茅台(新浪)")));
        SubjectListSource source =
                new RoutingSubjectListSource(
                        eastMoney,
                        sina,
                        "auto",
                        subjectSyncCenter(new AtomicReference<>(List.of("sina", "eastmoney"))));

        List<SubjectSnapshot> result = source.fetchAll(MarketSyncSpec.A_SHARE_STOCK);

        assertThat(result.get(0).name()).isEqualTo("贵州茅台(新浪)");
        verify(eastMoney, never()).fetchAll(any());
    }

    @Test
    void subjectList_primaryFails_backupRetriesWholeBucket_allFailThrowsWithSuppressed() {
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("东财列表失败"));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("新浪首页为空"));
        RoutingSubjectListSource source =
                new RoutingSubjectListSource(
                        eastMoney,
                        sina,
                        "auto",
                        subjectSyncCenter(new AtomicReference<>(List.of("eastmoney", "sina"))));

        Throwable thrown =
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> source.fetchAll(MarketSyncSpec.A_SHARE_STOCK));
        assertThat(thrown).hasMessageContaining("首页为空");
        assertThat(thrown.getSuppressed()).hasSize(1);
    }

    @Test
    void subjectList_hotSwitchAndCorruptChain_warnAndFallback() {
        AtomicReference<List<String>> chain = new AtomicReference<>(List.of("eastmoney"));
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("东财列表失败"));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(List.of(snapshot("贵州茅台(新浪)")));
        RoutingSubjectListSource source =
                new RoutingSubjectListSource(eastMoney, sina, "auto", subjectSyncCenter(chain));

        // 首轮：链 [eastmoney] 主源失败 → 无备选 → 桶失败（链热读自 subject.sync.fallbackChain）
        assertThatThrownBy(() -> source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .hasMessageContaining("东财列表失败");
        verify(sina, never()).fetchAll(any());

        // 页面保存等价动作：链补上新浪备选 → 下一轮自动降级新浪
        chain.set(List.of("eastmoney", "sina"));
        assertThat(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK).get(0).name())
                .isEqualTo("贵州茅台(新浪)");

        // DB 手改坏链（tencent 不在列表源注册表）：WARN 回落全链（同上仍可降级）
        chain.set(List.of("tencent"));
        assertThat(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK).get(0).name())
                .isEqualTo("贵州茅台(新浪)");
    }

    @Test
    void subjectList_legacyAShareSourceStillFolds_whenChainAbsent() {
        // 读取兼容：存量 DB 只有旧键 aShareSource（无 fallbackChain）——auto 折算全链
        EastMoneyListClient eastMoney = mock(EastMoneyListClient.class);
        SinaSubjectListClient sina = mock(SinaSubjectListClient.class);
        when(eastMoney.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .thenThrow(new IllegalStateException("东财列表失败"));
        when(sina.fetchAll(MarketSyncSpec.A_SHARE_STOCK)).thenReturn(List.of(snapshot("贵州茅台(新浪)")));
        AtomicReference<String> legacy = new AtomicReference<>("auto");
        RoutingSubjectListSource source =
                new RoutingSubjectListSource(
                        eastMoney, sina, "auto", legacySubjectSyncCenter(legacy));

        assertThat(source.fetchAll(MarketSyncSpec.A_SHARE_STOCK).get(0).name())
                .isEqualTo("贵州茅台(新浪)");

        // 旧键单值折算单元素链（强制单源，排障口径）
        legacy.set("eastmoney");
        assertThatThrownBy(() -> source.fetchAll(MarketSyncSpec.A_SHARE_STOCK))
                .hasMessageContaining("东财列表失败");
    }

    // ---- fixtures ----

    /** 行情源配置中心 stub：dataSource(QUOTE) 按当前链值现算视图（换值 = 换快照，等价页面保存）。 */
    private static ConfigCenter quoteCenter(AtomicReference<List<String>> chain) {
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
                                        Map.of(),
                                        chain.get()));
        return center;
    }

    /** 估值源配置中心 stub（同行情款）。 */
    private static ConfigCenter valuationCenter(AtomicReference<List<String>> chain) {
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
                                        Map.of(),
                                        chain.get()));
        return center;
    }

    /** 标的池同步配置中心 stub：document("subject.sync") 带 fallbackChain（aShareSource 缺省折算口径）。 */
    private static ConfigCenter subjectSyncCenter(AtomicReference<List<String>> chain) {
        ConfigCenter center = mock(ConfigCenter.class);
        when(center.document(SubjectSyncConfigValidator.KEY))
                .thenAnswer(
                        inv ->
                                Optional.of(
                                        JSON.createObjectNode()
                                                .set(
                                                        "fallbackChain",
                                                        JSON.valueToTree(chain.get()))));
        return center;
    }

    /** 标的池同步配置中心 stub：document("subject.sync") 只带旧键 aShareSource（存量 DB 形状）。 */
    private static ConfigCenter legacySubjectSyncCenter(AtomicReference<String> aShareSource) {
        ConfigCenter center = mock(ConfigCenter.class);
        when(center.document(SubjectSyncConfigValidator.KEY))
                .thenAnswer(
                        inv ->
                                Optional.of(
                                        JSON.createObjectNode()
                                                .put("aShareSource", aShareSource.get())));
        return center;
    }

    private static Map<String, Object> eastFields(String price) {
        Map<String, Object> fields = new LinkedHashMap<>();
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
    private static Map<String, Object> tencentFields(String price) {
        Map<String, Object> fields = new LinkedHashMap<>();
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

    private static SubjectSnapshot snapshot(String name) {
        return new SubjectSnapshot(
                "SH600519", name, null, "1.600519", MarketSyncSpec.A_SHARE_STOCK);
    }

    private static Subject aShareSubject() {
        return Subject.reconstruct(
                1L,
                SubjectCode.of("SH600519"),
                Market.A_SHARE,
                SubjectType.STOCK,
                "贵州茅台",
                Map.of("eastmoney", "1.600519", "tushare", "600519.SH"),
                "白酒",
                SubjectStatus.ENABLED,
                1L,
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"));
    }
}
