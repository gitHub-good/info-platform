package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 批次三 json_api 双源 fixture 单测（M18 T151/T152，REQ-20260926-15）：央视财经（jingji 首页客户端渲染 → 页面自身 jsonp 数据端点
 * {@code economy_zixun(...)}，沿工信部检索 API 先例）与东方财富·财经频道（np-weblist column=352，与在役 em_headlines 同宿主同构）
 * 目录配置对真实响应截样本的解析正确性（stripWrapper 剥 jsonp 包裹/focus_date 与 showTime 墙钟/ID 游标）。零外呼。
 */
class JsonApiFeedFetcherBatchThreeTest {

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

    @Test
    void parse_cctvEconomy_jsonpWrapperStrippedAndWallClockMapped() {
        InfoSource source = catalogSource("cctv_economy");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/cctv-economy-jsonp-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实截样本 10 条（2026-09-26 预检，economy_zixun_1.jsonp data.list）
        assertThat(items).hasSize(10);
        RawFeedItem first = items.get(0);
        // id 为 ARTI 段（与 URL 尾段一致），非数值 → 目录 cursorType=NONE
        assertThat(first.externalId()).isEqualTo("ARTI2IIA6dw2eXTU5iO97yjI260926");
        assertThat(first.title()).contains("贴息扩围、额度上调");
        assertThat(first.url())
                .isEqualTo("https://news.cctv.com/2026/09/26/ARTI2IIA6dw2eXTU5iO97yjI260926.shtml");
        // focus_date 2026-09-26 07:32:07（Asia/Shanghai 墙钟）→ UTC 前一日 23:32:07
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-25T23:32:07Z"));
        assertThat(first.cursorValue()).isNull();
    }

    @Test
    void parse_emFinanceColumn_sameHostShapeAsHeadlinesWithIdCursor() {
        InfoSource source = catalogSource("em_finance_column");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/em-column352-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实截样本 8 条（2026-09-26 预检，np-weblist getNewsByColumns column=352 data.list）
        assertThat(items).hasSize(8);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("202609263884389040");
        assertThat(first.title()).contains("上海金融监管局");
        assertThat(first.url())
                .isEqualTo("http://bank.eastmoney.com/news/1174,202609263884389040.html");
        // showTime 2026-09-26 13:45:31 墙钟 → UTC 05:45:31；code 数值 ID 游标（沿 em_headlines 口径）
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-26T05:45:31Z"));
        assertThat(first.cursorValue()).isEqualTo("202609263884389040");
    }
}
