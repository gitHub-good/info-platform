package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 批次一一级 JSON 三源 fixture 单测（M14 T110，ADR-0042「实测→映射→fixture」模式）：东财 7×24 / 同花顺 / 澎湃
 * 目录配置对真实响应截样本的解析正确性（条数/字段/游标/URL 合成）+ 同花顺无 charset JSON 的 UTF-8 解码回归（GBK 顾虑闭环—— 普查 GBK 系
 * today_list HTML 通道，JSON 通道不涉及）。
 *
 * <p>东财宏观（preset 通道）见 {@code EastmoneyMacroAdapterTest}。fixture 均为 2026-09-25 预检真实响应截样本（3/3/2 条）。
 */
class JsonApiFeedFetcherBatchOneTest {

    private final SourceConfigCodec codec = new SourceConfigCodec();
    private final JsonApiFeedFetcher fetcher = new JsonApiFeedFetcher(RestClient.builder().build());

    private InfoSource catalogSource(String sourceCode) {
        InfoSourceCatalog.PresetEntry entry =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals(sourceCode))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("目录缺失: " + sourceCode));
        return InfoSource.create(
                entry.sourceCode(),
                entry.name(),
                entry.category(),
                entry.adapterType(),
                entry.adapterRef(),
                entry.endpoint(),
                codec.parse(entry.configJson()),
                entry.intervalMinutes(),
                true,
                true);
    }

    private static String fixture(String name) {
        try (InputStream in =
                JsonApiFeedFetcherBatchOneTest.class
                        .getClassLoader()
                        .getResourceAsStream("feed/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失: feed/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 读取失败: " + name, e);
        }
    }

    @Test
    void parse_eastmoney7x24_fastNewsListMappedWithWallClockCursor() {
        InfoSource source = catalogSource("em_fastnews_7x24");

        List<RawFeedItem> items =
                fetcher.parse(
                                fixture("em-724-fastnews-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实样本 3 条（2026-09-25 19:0x 快讯），newest-first
        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("202609253884311863");
        assertThat(first.title()).isEqualTo("美元指数跌破101关口");
        assertThat(first.summary()).contains("日内跌0.24%");
        // showTime 墙钟北京时间 19:06:53 → UTC 11:06:53
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-25T11:06:53Z"));
        // 源无直链字段：url 留空（externalId 兜底过滤线）、ID 游标即 externalId
        assertThat(first.url()).isNull();
        assertThat(first.cursorValue()).isEqualTo("202609253884311863");
    }

    @Test
    void parse_thsPushStock_epochSecondsAndDirectUrlMapped() {
        InfoSource source = catalogSource("ths_push_stock");

        List<RawFeedItem> items =
                fetcher.parse(
                                fixture("ths-push-stock-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        assertThat(items).hasSize(3);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("5249473");
        assertThat(first.title()).contains("比特币涨至85000美元上方");
        assertThat(first.summary()).contains("85224.3");
        // ctime=1790335028 Unix 秒 → ISO（毫秒截断）
        assertThat(first.publishedAt()).isEqualTo(Instant.ofEpochSecond(1790335028));
        // 源侧直链字段齐全
        assertThat(first.url()).isEqualTo("https://news.10jqka.com.cn/20260925/c680283850.shtml");
        assertThat(first.cursorValue()).isEqualTo("5249473");
    }

    @Test
    void parse_thepaperHotNews_epochMillisAndUrlSynthesized() {
        InfoSource source = catalogSource("thepaper_hotnews");

        List<RawFeedItem> items =
                fetcher.parse(
                                fixture("thepaper-hotnews-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        assertThat(items).hasSize(2);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("34147373");
        assertThat(first.title()).isEqualTo("习近平同美国总统特朗普会谈");
        // pubTimeLong Unix 毫秒（epoch_millis_to_iso，M14 引擎扩展）——2026-09-25 预检样本真实值
        assertThat(first.publishedAt()).isEqualTo(Instant.ofEpochMilli(1790280720106L));
        // 条目无直链字段 → urlTemplate 合成
        assertThat(first.url()).isEqualTo("https://www.thepaper.cn/newsDetail_forward_34147373");
        assertThat(first.cursorValue()).isEqualTo("34147373");
    }

    @Test
    void fetch_thsCharsetlessJson_decodedAsUtf8() {
        // GBK 顾虑闭环（普查注记 today_list 为 GBK HTML 通道）：push/stock 响应 application/json 无 charset，
        // Spring StringHttpMessageConverter 按 UTF-8 解码（Jackson 同口径）——中文不得乱码
        InfoSource source = catalogSource("ths_push_stock");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url =
                UriComponentsBuilder.fromUriString(source.getEndpoint())
                        .queryParam("page", 1)
                        .build()
                        .toUriString();
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                fixture("ths-push-stock-sample.json")
                                        .getBytes(StandardCharsets.UTF_8),
                                MediaType.APPLICATION_JSON));
        JsonApiFeedFetcher httpFetcher = new JsonApiFeedFetcher(builder.build());

        List<RawFeedItem> items = httpFetcher.fetch(source, FetchContext.firstPage(null)).items();

        assertThat(items).hasSize(3);
        assertThat(items.get(0).title()).contains("比特币涨至85000美元上方");
        server.verify();
    }
}
