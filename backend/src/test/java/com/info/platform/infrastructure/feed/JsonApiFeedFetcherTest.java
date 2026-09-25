package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * JsonApiFeedFetcher 单测（T102，方案 §4.3）：金十 JS 包装剥离 / 标准 JSON 根数组 / listPath 点分导航 / 缺失字段容错 / ID
 * 游标止步与补抓深翻（Mock server 拦截 page 参数，零外呼）。
 */
class JsonApiFeedFetcherTest {

    private static final String JIN10_BODY =
            """
            var newest=[\
            {"id":2002,"time":"2026-09-22 09:31","title":"美联储按兵不动","important_title":"<p>维持利率区间不变</p>"},\
            {"id":2001,"time":"2026-09-22 09:20","title":"沪指高开","important_title":""}];\
            """;

    private static SourceConfig jin10Config(CursorType cursorType) {
        return new SourceConfig(
                "",
                "var newest=",
                ";",
                List.of(
                        new SourceConfig.ItemMapping("id", "externalId", "to_string"),
                        new SourceConfig.ItemMapping("time", "publishedAt", "to_iso_datetime"),
                        new SourceConfig.ItemMapping("title", "title", "to_string"),
                        new SourceConfig.ItemMapping("important_title", "summary", "strip_html")),
                Map.of("User-Agent", "Mozilla/5.0 test", "Referer", "https://www.example.com"),
                null,
                null,
                cursorType,
                "externalId");
    }

    private static InfoSource jsonSource(SourceConfig config) {
        return InfoSource.create(
                "t102_json",
                "JSON 源",
                "快讯",
                AdapterType.JSON_API,
                null,
                "https://www.example.com/api.json",
                config,
                5,
                true,
                false);
    }

    private final JsonApiFeedFetcher fetcher = new JsonApiFeedFetcher(RestClient.builder().build());

    @Test
    void parse_jin10JsWrapper_strippedAndMapped() {
        InfoSource source = jsonSource(jin10Config(CursorType.ID));

        List<RawFeedItem> items =
                fetcher.parse(JIN10_BODY, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).externalId()).isEqualTo("2002");
        assertThat(items.get(0).title()).isEqualTo("美联储按兵不动");
        // to_iso_datetime：北京 09:31 → UTC 01:31
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-22T01:31:00Z"));
        // strip_html：richtext 抽纯文本；空串 → null summary
        assertThat(items.get(0).summary()).isEqualTo("维持利率区间不变");
        assertThat(items.get(1).summary()).isNull();
        // ID 游标值即 externalId
        assertThat(items.get(0).cursorValue()).isEqualTo("2002");
    }

    @Test
    void parse_standardRootArray_noWrapper() {
        String body =
                """
                [{"id":2,"title":"b","time":"2026-09-22 09:31"},{"id":1,"title":"a","time":"2026-09-22 09:20"}]\
                """;
        SourceConfig config =
                new SourceConfig(
                        null,
                        null,
                        null,
                        List.of(
                                new SourceConfig.ItemMapping("id", "externalId", "to_string"),
                                new SourceConfig.ItemMapping("title", "title", "to_string")),
                        null,
                        null,
                        null,
                        CursorType.NONE,
                        null);
        InfoSource source = jsonSource(config);

        List<RawFeedItem> items = fetcher.parse(body, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("b");
        assertThat(items.get(0).publishedAt()).isNull();
    }

    @Test
    void parse_nestedListPath_navigatesDotPath() {
        String body =
                """
                {"status":{"code":0},"result":{"data":[{"id":7,"title":"嵌套"}]}}\
                """;
        SourceConfig config =
                new SourceConfig(
                        "result.data",
                        null,
                        null,
                        List.of(
                                new SourceConfig.ItemMapping("id", "externalId", "to_string"),
                                new SourceConfig.ItemMapping("title", "title", "to_string")),
                        null,
                        null,
                        null,
                        CursorType.NONE,
                        null);
        InfoSource source = jsonSource(config);

        List<RawFeedItem> items = fetcher.parse(body, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("嵌套");
    }

    @Test
    void parse_missingFields_degradedNotFatal() {
        String body =
                """
                [{"id":3},{"title":"无 id 条目"},{"time":"2026-09-22 09:31"}]\
                """;
        SourceConfig config =
                new SourceConfig(
                        null,
                        null,
                        null,
                        List.of(
                                new SourceConfig.ItemMapping("id", "externalId", "to_string"),
                                new SourceConfig.ItemMapping(
                                        "time", "publishedAt", "to_iso_datetime"),
                                new SourceConfig.ItemMapping("title", "title", "to_string")),
                        null,
                        null,
                        null,
                        CursorType.NONE,
                        null);
        InfoSource source = jsonSource(config);

        List<RawFeedItem> items = fetcher.parse(body, source, FetchContext.firstPage(null)).items();

        // 字段缺失逐条降级：无 title / 无 time / 无 id 各自为 null（title 过滤归摄取层）
        assertThat(items).hasSize(3);
        assertThat(items.get(0).title()).isNull();
        assertThat(items.get(1).externalId()).isNull();
        assertThat(items.get(2).publishedAt()).isNotNull();
    }

    @Test
    void parse_listPathMiss_throwsFeedFetchException() {
        SourceConfig config =
                new SourceConfig(
                        "result.items",
                        null,
                        null,
                        List.of(new SourceConfig.ItemMapping("id", "externalId", "to_string")),
                        null,
                        null,
                        null,
                        CursorType.NONE,
                        null);
        InfoSource source = jsonSource(config);

        assertThatThrownBy(
                        () ->
                                fetcher.parse(
                                        "{\"result\":{}}", source, FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("listPath");
    }

    @Test
    void parse_garbageJson_throwsFeedFetchException() {
        InfoSource source = jsonSource(jin10Config(CursorType.NONE));

        assertThatThrownBy(
                        () ->
                                fetcher.parse(
                                        "not-json-at-all", source, FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class);
    }

    @Test
    void parse_idCursorStopsAtSeenItem() {
        InfoSource source = jsonSource(jin10Config(CursorType.ID));

        FetchResult result = fetcher.parse(JIN10_BODY, source, new FetchContext("2001", 1));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).externalId()).isEqualTo("2002");
        assertThat(result.truncated()).isFalse();
    }

    private static String pageBody(int id) {
        return "var newest=[{\"id\":%d,\"time\":\"2026-09-22 09:31\",\"title\":\"t%d\",\"important_title\":\"\"}];"
                .formatted(id, id);
    }

    @Test
    void parse_realJin10Shape_titleFallbackKeepsContentOnlyFlashes() {
        // 真实金十形态（2026-09-25 外呼复核，ADR-0042）：正文嵌 data 子对象，中文快讯 data.title 空
        String body =
                """
                var newest=[                {"id":20260925182244445800,"time":"2026-09-25 18:22:44","data":{"title":"","content":"现货白银向上触及65美元/盎司，日内涨1.81%。"}},                {"id":20260925182109549800,"time":"2026-09-25 18:21:09","data":{"title":"Iraq cuts Basra crude prices","content":"Iraq's state oil marketer offered cargoes at discounted prices."}}];                """;
        SourceConfig config =
                new SourceConfig(
                        "",
                        "var newest=",
                        ";",
                        List.of(
                                new SourceConfig.ItemMapping("id", "externalId", "to_string"),
                                new SourceConfig.ItemMapping(
                                        "time", "publishedAt", "to_iso_datetime"),
                                new SourceConfig.ItemMapping("data.title", "title", "to_string"),
                                new SourceConfig.ItemMapping(
                                        "data.content", "summary", "strip_html")),
                        null,
                        null,
                        null,
                        CursorType.ID,
                        "externalId");

        List<RawFeedItem> items =
                fetcher.parse(body, jsonSource(config), FetchContext.firstPage(null)).items();

        // 中文快讯：title 空以 content 补位（标题回落），summary 置空
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).contains("现货白银");
        assertThat(items.get(0).summary()).isNull();
        // 英文快讯：题文分立
        assertThat(items.get(1).title()).contains("Iraq cuts Basra");
        assertThat(items.get(1).summary()).contains("discounted prices");
        // 北京 18:22:44 → UTC 10:22:44
        assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-25T10:22:44Z"));
    }

    @Test
    void parse_jin10WrapperWhitespaceDrift_stillStripped() {
        // 2026-09-25 实测形态：var newest = [...]（= 两侧空格）——精确前缀不命中，回落首个 [ 截取（ADR-0042）
        String body =
                """
                var newest = [{"id":3001,"time":"2026-09-25 18:22:44","title":"空格漂移包装"},\
                {"id":3000,"time":"2026-09-25 18:20:00","title":"第二条"}];
                """;
        InfoSource source = jsonSource(jin10Config(CursorType.ID));

        List<RawFeedItem> items = fetcher.parse(body, source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).externalId()).isEqualTo("3001");
        assertThat(items.get(0).title()).isEqualTo("空格漂移包装");
    }

    @Test
    void fetch_backfillPages_pageParamAppendedAndTruncationFlagged() {
        // 深翻 2 页：page=1 与 page=2 各返回一条未见条目（游标 0），全程未见已见条目 → truncated
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String page1Url =
                UriComponentsBuilder.fromHttpUrl("https://www.example.com/api.json")
                        .queryParam("page", 1)
                        .build()
                        .toUriString();
        String page2Url =
                UriComponentsBuilder.fromHttpUrl("https://www.example.com/api.json")
                        .queryParam("page", 2)
                        .build()
                        .toUriString();
        server.expect(requestTo(page1Url))
                .andExpect(header("User-Agent", "Mozilla/5.0 test"))
                .andRespond(withSuccess(pageBody(2002), MediaType.APPLICATION_JSON));
        server.expect(requestTo(page2Url))
                .andRespond(withSuccess(pageBody(2001), MediaType.APPLICATION_JSON));
        JsonApiFeedFetcher httpFetcher = new JsonApiFeedFetcher(builder.build());
        InfoSource source = jsonSource(jin10Config(CursorType.ID));

        FetchResult result = httpFetcher.fetch(source, new FetchContext("0", 2));

        assertThat(result.items()).hasSize(2);
        assertThat(result.truncated()).isTrue();
        server.verify();
    }
}
