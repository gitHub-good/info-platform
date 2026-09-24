package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * CninfoAnnounceClient 单测（ADR-0034 T57）：orgId 映射缓存（命中/过期/刷新失败沿旧表/无旧表抛异常/未命中容错）、POST 查询契约与响应解析
 * （announcements 导航/epoch ms→Asia/Shanghai 时间/东财 flat 键映射/em 标签剔除/空响应）、详情直链拼接。全部
 * MockRestServiceServer 模拟（2026-09-24 架构 curl 实测口径），禁真实外呼。
 */
class CninfoAnnounceClientTest {

    private static final String QUERY_URL = "https://cninfo.test/new/hisAnnouncement/query";
    private static final String STOCK_LIST_URL = "https://cninfo.test/new/data/szse_stock.json";
    private static final String DETAIL_PREFIX = "https://static.cninfo.test/";
    private static final Duration TTL_24H = Duration.ofHours(24);
    private static final Duration TTL_ZERO = Duration.ZERO;

    /** orgId 映射表夹具（2026-09-24 实测口径节选）：文件名虽为 szse，沪深全量皆在内（600519 沪 gssh 前缀 / 000001 深 gssz 前缀）。 */
    private static final String STOCK_LIST_JSON =
            """
            [{"orgId":"gssh0600519","category":"A股","code":"600519","pinyin":"GZMT","zwjc":"贵州茅台"},
             {"orgId":"gssz0000001","category":"A股","code":"000001","pinyin":"PAYH","zwjc":"平安银行"},
             {"orgId":"","category":"A股","code":"899999","pinyin":"BAD","zwjc":"脏条目"}]
            """;

    /** 查询响应夹具（600519 实测口径：announcementId/Title/Time epoch ms/adjunctUrl，无 column_name 同款分类名）。 */
    private static final String QUERY_JSON =
            """
            {"announcements":[
              {"announcementId":"1225475868","announcementTitle":"贵州茅台:2026年半年度报告",
               "announcementTime":1786723200000,"secCode":"600519","secName":"贵州茅台",
               "adjunctUrl":"finalpage/2026-08-15/1225475868.PDF","columnId":"0101"},
              {"announcementId":"1225475869","announcementTitle":"贵州茅台:<em>业绩说明会</em>公告",
               "announcementTime":1786723200000,"secCode":"600519","secName":"贵州茅台",
               "adjunctUrl":"finalpage/2026-08-15/1225475869.PDF"}],
             "totalAnnouncement":486,"totalRecordNum":486,"hasMore":true,"pageNum":1}
            """;

    /** 1786723200000 ms = 2026-08-15T00:00+08:00（Asia/Shanghai 墙钟，实测为公告日 00:00）。 */
    private static final String NOTICE_DATE = "2026-08-15 00:00:00";

    @Test
    void fetch_postsQueryWithOrgId_mapsToEastMoneyFlatKeys() {
        Bound bound = bind(TTL_24H);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andExpect(method(HttpMethod.POST))
                // POST form 契约（ADR-0034 实测）：stock={code},{orgId} 复合格式 + 固定参数
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "stock=600519%2Cgssh0600519")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("column=szse")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("tabName=fulltext")))
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("isHLtitle=false")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pageNum=1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pageSize=3")))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.containsString("Mozilla")))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));

        Optional<List<Map<String, Object>>> result = bound.client.fetchAnnouncements("600519");

        bound.server.verify();
        assertThat(result).isPresent();
        List<Map<String, Object>> items = result.get();
        assertThat(items).hasSize(2);
        Map<String, Object> first = items.get(0);
        // 字段映射（方案 §4.2 核对表）：巨潮字段 → 东财公告 flat 键
        assertThat(first.get("art_code")).isEqualTo("1225475868");
        assertThat(first.get("title")).isEqualTo("贵州茅台:2026年半年度报告");
        assertThat(first.get("notice_date")).isEqualTo(NOTICE_DATE);
        assertThat(first.get("stock_code")).isEqualTo("600519");
        assertThat(first.get("short_name")).isEqualTo("贵州茅台");
        assertThat(first.get("adjunct_url")).isEqualTo("finalpage/2026-08-15/1225475868.PDF");
        // 巨潮无东财 column_name 同款分类名 → flat 不含该键（category 白名单语义缺失，ADR-0034）
        assertThat(first).doesNotContainKey("column_name");
        // 第二条：isHLtitle=false 已免高亮，防御性剔 <em> 标签
        assertThat(items.get(1).get("title")).isEqualTo("贵州茅台:业绩说明会公告");
    }

    @Test
    void orgIdTable_fetchedOnceWithinTtl_secondFetchCacheHit() {
        Bound bound = bind(TTL_24H);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));

        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();
        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();

        // orgId 表 24h 缓存：两次查询仅拉一次映射表（mock 严格单期待 + verify 证）
        bound.server.verify();
    }

    @Test
    void orgIdTable_expired_refetchedOnNextFetch() {
        Bound bound = bind(TTL_ZERO);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));

        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();
        // TTL=0 即每次过期：第二次取数重新拉映射表（刷新成功替换缓存）
        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();

        bound.server.verify();
    }

    @Test
    void orgIdTable_refreshFailsAfterExpiry_staleTableTolerated() {
        Bound bound = bind(TTL_ZERO);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));
        // 过期后刷新失败（500）：沿用旧表 stale 容忍，查询照常发出
        bound.server.expect(requestTo(STOCK_LIST_URL)).andRespond(withServerError());
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(withSuccess(QUERY_JSON, MediaType.APPLICATION_JSON));

        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();
        assertThat(bound.client.fetchAnnouncements("600519")).isPresent();

        bound.server.verify();
    }

    @Test
    void orgIdTable_refreshFails_noStaleTable_throwsForChainHandling() {
        Bound bound = bind(TTL_24H);
        bound.server.expect(requestTo(STOCK_LIST_URL)).andRespond(withServerError());

        // 无旧表可沿：异常上抛（该级失败，交链执行器按链语义处理）
        assertThatThrownBy(() -> bound.client.fetchAnnouncements("600519"))
                .isInstanceOf(RestClientException.class);
        bound.server.verify();
    }

    @Test
    void unknownStock_orgIdMiss_returnsEmptyWithoutQuery() {
        Bound bound = bind(TTL_24H);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));

        // 未命中标的容错（如港股/新股）：empty（→ 链下一级 / MISSING），不发查询 POST（严格单期待证）
        Optional<List<Map<String, Object>>> result = bound.client.fetchAnnouncements("00700");

        assertThat(result).isEmpty();
        bound.server.verify();
    }

    @Test
    void emptyAnnouncements_returnsEmpty() {
        Bound bound = bind(TTL_24H);
        bound.server
                .expect(requestTo(STOCK_LIST_URL))
                .andRespond(withSuccess(STOCK_LIST_JSON, MediaType.APPLICATION_JSON));
        bound.server
                .expect(requestTo(QUERY_URL))
                .andRespond(
                        withSuccess(
                                "{\"announcements\":null,\"totalRecordNum\":0}",
                                MediaType.APPLICATION_JSON));

        assertThat(bound.client.fetchAnnouncements("600519")).isEmpty();
        bound.server.verify();
    }

    @Test
    void detailUrlOf_prependsPrefix() {
        CninfoAnnounceClient client =
                new CninfoAnnounceClient(
                        RestClient.builder(), QUERY_URL, STOCK_LIST_URL, DETAIL_PREFIX, TTL_24H);

        assertThat(client.detailUrlOf("finalpage/2026-08-15/1225475868.PDF"))
                .isEqualTo("https://static.cninfo.test/finalpage/2026-08-15/1225475868.PDF");
    }

    @Test
    void defaults_seedUrlsMatchDataSourceDefaults() {
        // 构造期回落与 DataSourceDefaults 单一事实源一致（防两处定义漂移，对齐 ADR-0032）
        assertThat(CninfoAnnounceClient.DEFAULT_QUERY_URL)
                .isEqualTo(
                        com.info.platform.infrastructure.common.DataSourceDefaults.paramString(
                                com.info.platform.domain.aggregation.SourceCode.ANNOUNCE,
                                "cninfoQueryUrl"))
                .isEqualTo("https://www.cninfo.com.cn/new/hisAnnouncement/query");
        assertThat(CninfoAnnounceClient.DEFAULT_STOCK_LIST_URL)
                .isEqualTo("https://www.cninfo.com.cn/new/data/szse_stock.json");
        assertThat(CninfoAnnounceClient.DEFAULT_DETAIL_URL_PREFIX)
                .isEqualTo("https://static.cninfo.com.cn/");
    }

    // ---- helpers ----

    private record Bound(CninfoAnnounceClient client, MockRestServiceServer server) {}

    /** 绑定 mock 服务端的客户端（URL 指向 .test 域，禁真实外呼）。 */
    private static Bound bind(Duration orgIdTtl) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CninfoAnnounceClient client =
                new CninfoAnnounceClient(
                        builder, QUERY_URL, STOCK_LIST_URL, DETAIL_PREFIX, orgIdTtl);
        return new Bound(client, server);
    }

    /** 供其他测试类复用的替身构造（不发请求场景）。 */
    static CninfoAnnounceClient inactiveClient() {
        return new CninfoAnnounceClient(
                RestClient.builder(), QUERY_URL, STOCK_LIST_URL, DETAIL_PREFIX, TTL_24H);
    }
}
