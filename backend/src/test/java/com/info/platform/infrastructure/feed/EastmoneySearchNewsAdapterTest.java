package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * EastmoneySearchNewsAdapter 单测（M29 T253，Spike-E E-4a 实测契约）：全 Mock 无真实外呼。
 *
 * <p>覆盖：JSONP 剥壳与 result.cmsArticleWebOld[] 解析、{@code <em>} 高亮剥除、发布时间北京墙钟解析、市场解析（-hk/-us 后缀）、标的池
 * round-robin 轮转（回绕 + 跨轮推进）、单标的失败跳过继续、全部失败整轮失败、空池空轮转。夹具取 2026-09-29 探针实测响应（腾讯控股 5462 hits 样本）。
 */
class EastmoneySearchNewsAdapterTest {

    /** 2026-09-29 探针实测（节选两条，字段结构原样）：腾讯控股关键词搜索 JSONP 响应。 */
    static final String JSONP_BODY =
            "cb({\"bizCode\":\"\",\"bizMsg\":\"\",\"code\":0,\"extra\":{},\"hitsTotal\":5462,\"msg\":\"OK\","
                    + "\"result\":{\"cmsArticleWebOld\":["
                    + "{\"date\":\"2026-09-29 17:38:08\",\"image\":\"\",\"code\":\"202609293886552187\","
                    + "\"title\":\"<em>腾讯控股</em>：9月29日斥资10063.37万港元回购23.2万<em>股</em>\","
                    + "\"content\":\"南财智讯9月29日电，<em>腾讯控股</em>（00700.HK）发布翌日披露报表，9月29日，公司回购23.2万股。\","
                    + "\"mediaName\":\"南方财经网\",\"url\":\"http://finance.eastmoney.com/a/202609293886552187.html\"},"
                    + "{\"date\":\"2026-09-29 17:33:10\",\"image\":\"\",\"code\":\"202609293886552435\","
                    + "\"title\":\"<em>腾讯控股</em>于9月29日回购价值1.006亿港元的<em>股</em>份\","
                    + "\"content\":\"<em>腾讯控股</em>于9月29日回购价值1.006亿港元的股份。\","
                    + "\"mediaName\":\"财联社\",\"url\":\"http://finance.eastmoney.com/a/202609293886552435.html\"}]},"
                    + "\"searchId\":\"ebbf4d4\"});";

    @Test
    void fetch_hkSource_parsesJsonpStripsEmAndParsesBeijingTime() {
        Subject tencent = hkSubject("HK00700", "腾讯控股");
        Mocks mocks = bind(List.of(tencent));
        mocks.server
                .expect(requestTo(containsEncoded("腾讯控股")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(JSONP_BODY, MediaType.APPLICATION_JSON));

        FetchResult result = mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null));

        mocks.server.verify();
        assertThat(result.truncated()).isFalse();
        assertThat(result.items()).hasSize(2);
        RawFeedItem first = result.items().get(0); // newest-first：17:38:08 在前
        assertThat(first.externalId()).isEqualTo("202609293886552187");
        assertThat(first.title()).isEqualTo("腾讯控股：9月29日斥资10063.37万港元回购23.2万股"); // <em> 已剥除
        assertThat(first.summary()).startsWith("南财智讯9月29日电，腾讯控股（00700.HK）");
        assertThat(first.url()).isEqualTo("http://finance.eastmoney.com/a/202609293886552187.html");
        assertThat(first.author()).isEqualTo("南方财经网");
        // 北京墙钟 2026-09-29 17:38:08 → UTC 09:38:08
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-29T09:38:08Z"));
        assertThat(first.cursorValue()).isNull(); // cursorType=NONE
    }

    @Test
    void fetch_marketResolution_bySourceCodeSuffix() {
        assertThat(EastmoneySearchNewsAdapter.marketOf(hkSource())).isEqualTo(Market.HK);
        assertThat(EastmoneySearchNewsAdapter.marketOf(usSource())).isEqualTo(Market.US);
        assertThatThrownBy(() -> EastmoneySearchNewsAdapter.marketOf(sourceOf("em-search-cn")))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("em-search-cn");
    }

    @Test
    void fetch_roundRobin_advancesAndWrapsAcrossTicks() {
        // 池 3 只、每 tick 2 只：首轮 [0,1]、次轮 [2,0]（回绕）——轮转位为进程内原子计数（方案 C6「游标存
        // cursor_value」的实现偏差回注：环形指针与单调游标语义冲突，重启回卷头由唯一索引幂等吸收）
        Subject a = hkSubject("HK00700", "腾讯控股");
        Subject b = hkSubject("HK03888", "金山软件");
        Subject c = hkSubject("HK09988", "阿里巴巴");
        Mocks mocks = bind(List.of(a, b, c));
        // 四次请求期望一次性声明（expectation 按序消费）：首轮 [腾讯, 金山] → 次轮 [阿里, 腾讯（回绕）]
        mocks.server
                .expect(requestTo(containsEncoded("腾讯控股")))
                .andRespond(withSuccess(emptyArticles("腾讯控股"), MediaType.APPLICATION_JSON));
        mocks.server
                .expect(requestTo(containsEncoded("金山软件")))
                .andRespond(withSuccess(emptyArticles("金山软件"), MediaType.APPLICATION_JSON));
        mocks.server
                .expect(requestTo(containsEncoded("阿里巴巴")))
                .andRespond(withSuccess(emptyArticles("阿里巴巴"), MediaType.APPLICATION_JSON));
        mocks.server
                .expect(requestTo(containsEncoded("腾讯控股")))
                .andRespond(withSuccess(emptyArticles("腾讯控股"), MediaType.APPLICATION_JSON));

        FetchResult first = mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null));
        assertThat(first.items()).isEmpty(); // 空命中合法轮（零条目）
        mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null)); // 同一实例次轮（轮转位推进）
        mocks.server.verify();
    }

    @Test
    void fetch_singleSubjectFailure_skipsAndContinues() {
        Subject a = hkSubject("HK00700", "腾讯控股");
        Subject b = hkSubject("HK03888", "金山软件");
        Mocks mocks = bind(List.of(a, b));
        mocks.server.expect(requestTo(containsEncoded("腾讯控股"))).andRespond(withServerError());
        mocks.server
                .expect(requestTo(containsEncoded("金山软件")))
                .andRespond(withSuccess(JSONP_BODY, MediaType.APPLICATION_JSON));

        FetchResult result = mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(2); // 失败标的跳过、其余照常
    }

    @Test
    void fetch_allSubjectsFail_wholeRoundFails() {
        Subject a = hkSubject("HK00700", "腾讯控股");
        Mocks mocks = bind(List.of(a));
        mocks.server.expect(requestTo(containsEncoded("腾讯控股"))).andRespond(withServerError());

        assertThatThrownBy(() -> mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("全部标的搜索失败");
    }

    @Test
    void fetch_emptyPool_orDisabledOnly_emptyRound() {
        Mocks mocks = bind(List.of());
        assertThat(mocks.adapter.fetch(hkSource(), FetchContext.firstPage(null)).items()).isEmpty();

        Subject disabled =
                Subject.reconstruct(
                        null,
                        SubjectCode.of("HK00005"),
                        Market.HK,
                        SubjectType.STOCK,
                        "汇丰控股",
                        null,
                        "银行",
                        SubjectStatus.DISABLED,
                        0,
                        null,
                        null);
        Mocks disabledOnly = bind(List.of(disabled));
        // 停用行不入轮转池（loadPool 过滤 status=1）——零请求零条目
        assertThat(disabledOnly.adapter.fetch(hkSource(), FetchContext.firstPage(null)).items())
                .isEmpty();
        disabledOnly.server.verify();
    }

    @Test
    void stripJsonp_stripsCallbackShell() {
        assertThat(EastmoneySearchNewsAdapter.stripJsonp("cb({\"a\":1});")).isEqualTo("{\"a\":1}");
        assertThat(EastmoneySearchNewsAdapter.stripJsonp("  cb({\"a\":1})  "))
                .isEqualTo("{\"a\":1}");
        assertThatThrownBy(() -> EastmoneySearchNewsAdapter.stripJsonp("not jsonp"))
                .isInstanceOf(FeedFetchException.class);
    }

    // ---- helpers ----

    private record Mocks(
            EastmoneySearchNewsAdapter adapter,
            SubjectRepository subjectRepository,
            MockRestServiceServer server,
            RestClient.Builder builder) {}

    private Mocks bind(List<Subject> pool) {
        SubjectRepository repository = mock(SubjectRepository.class);
        when(repository.loadBucket(any(Market.class), any(SubjectType.class))).thenReturn(pool);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastmoneySearchNewsAdapter adapter =
                new EastmoneySearchNewsAdapter(builder.build(), repository, 2, 5, 0);
        return new Mocks(adapter, repository, server, builder);
    }

    private static InfoSource hkSource() {
        return sourceOf("em-search-hk");
    }

    private static InfoSource usSource() {
        return sourceOf("em-search-us");
    }

    private static InfoSource sourceOf(String sourceCode) {
        return InfoSource.create(
                sourceCode,
                "东方财富·个股资讯",
                "港股",
                AdapterType.PRESET,
                EastmoneySearchNewsAdapter.BEAN_NAME,
                "https://search-api-web.eastmoney.com/search/jsonp",
                new SourceConfig(
                        null, null, null, null, null, null, null, CursorType.NONE, null, null),
                30,
                true,
                true);
    }

    private static Subject hkSubject(String code, String name) {
        return Subject.reconstruct(
                null,
                SubjectCode.of(code),
                Market.HK,
                SubjectType.STOCK,
                name,
                null,
                "软件服务",
                SubjectStatus.ENABLED,
                0,
                null,
                null);
    }

    private static String emptyArticles(String keyword) {
        return "cb({\"code\":0,\"result\":{\"cmsArticleWebOld\":[]},\"keyword\":\""
                + keyword
                + "\"})";
    }

    /** 关键词所在 param 片段的 URL 编码匹配器（URLEncoder 逐字符映射，全串编码包含子串编码）。 */
    private static org.hamcrest.Matcher<String> containsEncoded(String keyword) {
        return org.hamcrest.Matchers.containsString(
                URLEncoder.encode("\"keyword\":\"" + keyword + "\"", StandardCharsets.UTF_8)
                        .replace("+", "%20"));
    }
}
