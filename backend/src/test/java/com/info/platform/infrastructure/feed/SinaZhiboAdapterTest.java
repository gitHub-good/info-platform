package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * SinaZhiboAdapter 单测（T106，方案 §3.4）：fixture 为 2026-09-25 真实响应截样本（id/rich_text/create_time/docurl
 * 实测口径， ADR-0042）；richtext 清洗 / ID 数值游标止步 / is_delete 跳过 / 深翻 page 参数与截断信号 / 结构漂移防御。零外呼。
 */
class SinaZhiboAdapterTest {

    private static final String ENDPOINT = "https://zhibo.sina.com.cn/api/zhibo/feed?zhibo_id=152";

    private static InfoSource presetSource() {
        // 与 InfoSourceCatalog.SINA_ZHIBO 同口径（cursorType=ID + cursorField=externalId）
        return InfoSource.create(
                "t106_sina",
                "新浪财经·7×24",
                "快讯",
                AdapterType.PRESET,
                SinaZhiboAdapter.BEAN_NAME,
                ENDPOINT,
                new SourceConfig(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        CursorType.ID,
                        "externalId",
                        null),
                5,
                true,
                true);
    }

    private static String fixture() {
        try (InputStream in =
                SinaZhiboAdapterTest.class
                        .getClassLoader()
                        .getResourceAsStream("feed/sina-zhibo-sample.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失: feed/sina-zhibo-sample.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 读取失败", e);
        }
    }

    private final SinaZhiboAdapter adapter = new SinaZhiboAdapter(RestClient.builder().build());

    @Test
    void parsePage_mapsRealFields_richtextCleanedAndTimeConverted() {
        List<RawFeedItem> items = adapter.parsePage(fixture(), presetSource());

        // fixture 3 条实测样本（newest-first：5113475 / 5113474 / 5113473）
        assertThat(items).hasSize(3);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("5113475");
        assertThat(newest.title()).contains("加拿大国家银行");
        assertThat(newest.title()).doesNotContain("<");
        // create_time 北京 18:31:05 → UTC 10:31:05（源侧墙钟口径）
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-25T10:31:05Z"));
        assertThat(newest.url()).startsWith("https://finance.sina.cn/7x24/");
        assertThat(newest.author()).isEqualTo("新浪财经");
        // ID 数值游标值即 externalId
        assertThat(newest.cursorValue()).isEqualTo("5113475");
    }

    @Test
    void fetch_idCursorStopsAtSeenItem() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(pageUrl(1)))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        SinaZhiboAdapter httpAdapter = new SinaZhiboAdapter(builder.build());

        // 已见 5113474 → 仅 5113475 一条新条目，未触页上限不截断
        FetchResult result = httpAdapter.fetch(presetSource(), new FetchContext("5113474", 1));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).externalId()).isEqualTo("5113475");
        assertThat(result.truncated()).isFalse();
        server.verify();
    }

    @Test
    void fetch_backfillPages_truncatedWhenNeverCaughtUp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(pageUrl(1)))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(pageUrl(2)))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        SinaZhiboAdapter httpAdapter = new SinaZhiboAdapter(builder.build());

        // 游标早于全部样本（深翻 2 页仍全未见）→ 全量返回 + truncated（ADR-0041）
        FetchResult result = httpAdapter.fetch(presetSource(), new FetchContext("1", 2));

        assertThat(result.items()).hasSize(6);
        assertThat(result.truncated()).isTrue();
        server.verify();
    }

    @Test
    void parsePage_skipsDeletedAndBlankEntries() {
        String body =
                """
                {"result":{"data":{"feed":{"list":[
                  {"id":9003,"rich_text":"正常条目","create_time":"2026-09-25 18:00:00","is_delete":0},
                  {"id":9002,"rich_text":"已删条目","create_time":"2026-09-25 17:59:00","is_delete":1},
                  {"id":9001,"rich_text":"  <p> </p> ","create_time":"2026-09-25 17:58:00","is_delete":0}
                ]}}}}
                """;

        List<RawFeedItem> items = adapter.parsePage(body, presetSource());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).externalId()).isEqualTo("9003");
    }

    @Test
    void parsePage_structureDrift_throwsWithSourceContext() {
        assertThatThrownBy(() -> adapter.parsePage("{\"result\":{\"data\":{}}}", presetSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("结构漂移");
        assertThatThrownBy(() -> adapter.parsePage("not-json", presetSource()))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void parsePage_noneCursorType_noCursorValueProduced() {
        InfoSource noneCursor =
                InfoSource.create(
                        "t106_sina_none",
                        "新浪财经·7×24",
                        "快讯",
                        AdapterType.PRESET,
                        SinaZhiboAdapter.BEAN_NAME,
                        ENDPOINT,
                        SourceConfig.empty(),
                        5,
                        true,
                        true);

        List<RawFeedItem> items = adapter.parsePage(fixture(), noneCursor);

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).cursorValue()).isNull();
    }

    private static String pageUrl(int page) {
        return UriComponentsBuilder.fromHttpUrl(ENDPOINT)
                .queryParam("page", page)
                .queryParam("page_size", 20)
                .build()
                .toUriString();
    }
}
