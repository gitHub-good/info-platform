package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
import com.info.platform.domain.aggregation.SourceProviders;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AnnounceSourceAdapter 巨潮备选降级单测（ADR-0034 T57，修前红回归用例）：东财 {@code np-anotice-stock}
 * 失败/空响应时公告分区裸奔（ISSUE-A 实证东财财务/公告两端口同日 WAF 收紧）—— 自动降级巨潮资讯（cninfo）重拉，经东财公告 flat 中间结构映射（{@code
 * eastmoney-announce.json} 零改动）。
 *
 * <p>覆盖：注册表默认链 [eastmoney, cninfo] / auto 东财成功不碰巨潮 / 东财失败降级巨潮 OK（字段映射 + 来源标注 + 详情直链 + category
 * 缺失语义）/ 双失败 MISSING / 强制 ["cninfo"] 单源 / 空链仅主源 / 缺代码不发请求 / 链热切换。 client 层行为（orgId 缓存/POST 契约/epoch
 * 时间）见 {@code CninfoAnnounceClientTest}。
 */
class AnnounceSourceAdapterFallbackTest {

    private static final String EAST_URL = "https://np-anotice-stock.test/api/security/ann";
    private static final String EAST_REFERER = "https://data.eastmoney.com/";
    private static final String EAST_DETAIL_TEMPLATE =
            "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf";
    private static final String CNINFO_QUERY_URL = "https://cninfo.test/new/hisAnnouncement/query";
    private static final String CNINFO_STOCK_LIST_URL =
            "https://cninfo.test/new/data/szse_stock.json";
    private static final String CNINFO_DETAIL_PREFIX = "https://static.cninfo.test/";
    private static final Duration ORG_ID_TTL = Duration.ofHours(24);

    /** 东财空响应形态（data.list 空数组——ISSUE-A WAF 拒绝/代码不存在等，触发降级而非当无数据裸奔）。 */
    private static final String EAST_EMPTY_BODY =
            "{\"data\":{\"list\":[],\"page_index\":1,\"page_size\":3,\"total_hits\":0},"
                    + "\"error\":\"\",\"success\":1}";

    /** orgId 映射表夹具（实测口径节选：szse 文件名沪深全量皆在内）。 */
    private static final String CNINFO_STOCK_LIST_JSON =
            """
            [{"orgId":"gssh0600519","category":"A股","code":"600519","pinyin":"GZMT","zwjc":"贵州茅台"}]
            """;

    /** 巨潮查询响应（600519 实测口径：announcementTime epoch ms = 2026-08-15 00:00 Asia/Shanghai）。 */
    private static final String CNINFO_QUERY_JSON =
            """
            {"announcements":[
              {"announcementId":"1225475868","announcementTitle":"贵州茅台:2026年半年度报告",
               "announcementTime":1786723200000,"secCode":"600519","secName":"贵州茅台",
               "adjunctUrl":"finalpage/2026-08-15/1225475868.PDF"}],
             "totalRecordNum":486,"hasMore":true,"pageNum":1}
            """;

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
    void registry_defaultChain_eastmoneyThenCninfo() {
        // 注册表补行即默认链（auto 语义，存量 DB 行无 fallbackChain 时自动生效）——ADR-0034 §3
        assertThat(SourceProviders.providers(SourceCode.ANNOUNCE))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.CNINFO);
        assertThat(SourceProvider.fromCode("cninfo")).isEqualTo(SourceProvider.CNINFO);
        assertThat(SourceProvider.CNINFO.displayName()).isEqualTo("巨潮资讯");
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyAnnounceClient(
                                RestClient.builder(),
                                EAST_URL,
                                3,
                                EAST_DETAIL_TEMPLATE,
                                EAST_REFERER),
                        new CninfoAnnounceClient(
                                RestClient.builder(),
                                CNINFO_QUERY_URL,
                                CNINFO_STOCK_LIST_URL,
                                CNINFO_DETAIL_PREFIX,
                                ORG_ID_TTL));
        assertThat(adapter.currentChain())
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.CNINFO);
    }

    @Test
    void auto_eastMoneyOk_cninfoNeverCalled() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess(
                                                        eastOkBody(), MediaType.APPLICATION_JSON)),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.cninfo().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告");
        assertThat(itemsOf(result).get(0).get("category")).isEqualTo("其他");
    }

    @Test
    void auto_eastMoneyHttpError_fallsBackToCninfoWithMappedFields() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withServerError()),
                        this::expectCninfoPages);

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        bound.cninfo().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告→巨潮资讯备选");
        // 巨潮条目 → 东财 flat 中间结构 → 既有 eastmoney-announce.json 映射零改动
        Map<String, Object> first = itemsOf(result).get(0);
        assertThat(first.get("externalId")).isEqualTo("1225475868");
        assertThat(first.get("title")).isEqualTo("贵州茅台:2026年半年度报告");
        assertThat(first.get("publishedAt")).isEqualTo("2026-08-15T00:00:00");
        assertThat(first.get("externalCode")).isEqualTo("600519");
        assertThat(first.get("subjectName")).isEqualTo("贵州茅台");
        // url 为构造项：adjunctUrl 拼 static.cninfo.com.cn 直链（ADR-0034 实测 200 application/pdf）
        assertThat(first.get("url"))
                .isEqualTo("https://static.cninfo.test/finalpage/2026-08-15/1225475868.PDF");
        // 巨潮无东财 column_name 同款分类名 → category 白名单语义缺失不产出（ADR-0034 §3.2）
        assertThat(first).doesNotContainKey("category");
    }

    @Test
    void auto_eastMoneyEmptyList_fallsBackToCninfo() {
        // 空响应与「无数据」不可区分 → 降级两可（东财 9501 类事故的同款口径，ADR-0034 §1）
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(
                                                withSuccess(
                                                        EAST_EMPTY_BODY,
                                                        MediaType.APPLICATION_JSON)),
                        this::expectCninfoPages);

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(result.getSource()).isEqualTo("东方财富公告→巨潮资讯备选");
        assertThat(itemsOf(result)).hasSize(1);
    }

    @Test
    void auto_bothHttpError_degradesToMissing() {
        Bound bound =
                bind(
                        null,
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withServerError()),
                        server -> {
                            server.expect(requestTo(CNINFO_STOCK_LIST_URL))
                                    .andRespond(withServerError());
                        });

        SourceResult result = bound.adapter().fetch(aShareSubject());

        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
        assertThat(result.getData()).isEmpty();
    }

    @Test
    void forcedCninfoSingleSource_eastMoneyNeverCalled() {
        Bound bound =
                bind(
                        new AtomicReference<>(List.of("cninfo")),
                        server -> {},
                        this::expectCninfoPages);

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.east().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.OK);
        // 强制单源链位 0 标注：链首 provider + 业务名（排障入口，页面可编）
        assertThat(result.getSource()).isEqualTo("巨潮资讯公告");
        assertThat(itemsOf(result).get(0).get("externalId")).isEqualTo("1225475868");
    }

    @Test
    void emptyChain_primaryOnly_noFallbackAttempt() {
        Bound bound =
                bind(
                        new AtomicReference<>(List.of()),
                        server ->
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withServerError()),
                        server -> {});

        SourceResult result = bound.adapter().fetch(aShareSubject());

        bound.cninfo().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    @Test
    void missingCodes_returnsMissingWithoutHttpCall() {
        // 既有语义保留：两路径代码均缺 → MISSING 不发任何 HTTP（链消费点改造后不回退该防御）
        Bound bound = bind(null, server -> {}, server -> {});

        SourceResult result =
                bound.adapter()
                        .fetch(
                                Subject.reconstruct(
                                        1L,
                                        SubjectCode.of("SH600519"),
                                        Market.A_SHARE,
                                        SubjectType.STOCK,
                                        "贵州茅台",
                                        Map.of("tushare", "600519.SH"),
                                        "白酒",
                                        SubjectStatus.ENABLED,
                                        1L,
                                        Instant.parse("2026-09-20T00:00:00Z"),
                                        Instant.parse("2026-09-20T00:00:00Z")));

        bound.east().verify();
        bound.cninfo().verify();
        assertThat(result.getStatus()).isEqualTo(SourceStatus.MISSING);
    }

    @Test
    void chainHotSwitch_takesEffectOnNextFetch() {
        AtomicReference<List<String>> chain = new AtomicReference<>(List.of("eastmoney"));
        Bound bound =
                bind(
                        chain,
                        server -> {
                            // 两次取数均先走东财（auto 链首恒东财，恢复回归靠每轮先试主源）——各期待一次失败
                            for (int i = 0; i < 2; i++) {
                                server.expect(requestTo(containsString("stock_list=600519")))
                                        .andRespond(withServerError());
                            }
                        },
                        this::expectCninfoPages);

        // 首轮：链 [eastmoney] 主源失败 → 无备选 → MISSING（fetchFresh 绕缓存）
        assertThat(bound.adapter().fetchFresh(aShareSubject()).getStatus())
                .isEqualTo(SourceStatus.MISSING);

        // 页面保存等价动作：链补上巨潮备选 → 下一次取数即新链（热生效，无重启）
        chain.set(List.of("eastmoney", "cninfo"));

        SourceResult second = bound.adapter().fetchFresh(aShareSubject());
        assertThat(second.getStatus()).isEqualTo(SourceStatus.OK);
        assertThat(second.getSource()).isEqualTo("东方财富公告→巨潮资讯备选");
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(SourceResult result) {
        return (List<Map<String, Object>>) result.getData().get("items");
    }

    private static String eastOkBody() {
        return """
                {"data":{"list":[
                  {"art_code":"AN202608141827994407",
                   "title":"贵州茅台:贵州茅台关于召开2026年半年度业绩说明会的公告",
                   "notice_date":"2026-08-15 00:00:00",
                   "codes":[{"short_name":"贵州茅台","stock_code":"600519"}],
                   "columns":[{"column_name":"其他"}]}],
                   "page_index":1,"page_size":3,"total_hits":1},"error":"","success":1}
                """;
    }

    /** 巨潮双请求期望：orgId 映射表 + 查询 POST（orgId 表 24h TTL 内本测试单次取数各一请求）。 */
    private void expectCninfoPages(MockRestServiceServer server) {
        server.expect(requestTo(CNINFO_STOCK_LIST_URL))
                .andRespond(withSuccess(CNINFO_STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(CNINFO_QUERY_URL))
                .andRespond(withSuccess(CNINFO_QUERY_JSON, MediaType.APPLICATION_JSON));
    }

    /** 公告源配置中心 stub：dataSource(ANNOUNCE) 按当前链值现算视图（换值 = 换快照，等价页面保存）。 */
    private static ConfigCenter announceCenter(AtomicReference<List<String>> chain) {
        ConfigCenter center = Mockito.mock(ConfigCenter.class);
        when(center.dataSource(SourceCode.ANNOUNCE))
                .thenAnswer(
                        inv ->
                                new RuntimeDataSource(
                                        SourceCode.ANNOUNCE,
                                        true,
                                        RuntimeDataSource.Mode.REAL,
                                        2000,
                                        0,
                                        300,
                                        30,
                                        Map.of(),
                                        chain.get()));
        return center;
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

    private record Bound(
            AnnounceSourceAdapter adapter,
            MockRestServiceServer east,
            MockRestServiceServer cninfo) {}

    /** 绑定双源 mock 服务端；chain 为 null 时不接配置中心（auto 默认链 = 注册表全链）。 */
    private Bound bind(
            AtomicReference<List<String>> chain,
            java.util.function.Consumer<MockRestServiceServer> eastExpect,
            java.util.function.Consumer<MockRestServiceServer> cninfoExpect) {
        RestClient.Builder eastBuilder = RestClient.builder();
        MockRestServiceServer eastServer = MockRestServiceServer.bindTo(eastBuilder).build();
        eastExpect.accept(eastServer);
        RestClient.Builder cninfoBuilder = RestClient.builder();
        MockRestServiceServer cninfoServer = MockRestServiceServer.bindTo(cninfoBuilder).build();
        cninfoExpect.accept(cninfoServer);
        AnnounceSourceAdapter adapter =
                new AnnounceSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        new EastMoneyAnnounceClient(
                                eastBuilder, EAST_URL, 3, EAST_DETAIL_TEMPLATE, EAST_REFERER),
                        new CninfoAnnounceClient(
                                cninfoBuilder,
                                CNINFO_QUERY_URL,
                                CNINFO_STOCK_LIST_URL,
                                CNINFO_DETAIL_PREFIX,
                                ORG_ID_TTL));
        if (chain != null) {
            adapter.configCenter = announceCenter(chain);
        }
        return new Bound(adapter, eastServer, cninfoServer);
    }
}
