package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 批次二 JSON 源 fixture 单测（M17 T140，ADR-0042「实测→映射→fixture」模式）：东财要闻频道目录配置对真实响应截样本的
 * 解析正确性（条数/字段/直链/墙钟游标）。fixture 为 2026-09-22 预检真实响应截样本（5 条）。
 */
class JsonApiFeedFetcherBatchTwoTest {

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
    void parse_eastmoneyHeadlines_listMappedWithDirectUrlAndIdCursor() {
        InfoSource source = catalogSource("em_headlines");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/em-headlines-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 真实样本 5 条（2026-09-26 11:4x 要闻），newest-first
        assertThat(items).hasSize(5);
        RawFeedItem first = items.get(0);
        assertThat(first.externalId()).isEqualTo("202609263884382975");
        assertThat(first.title()).contains("石沱长江大桥公路桥顺利合龙");
        assertThat(first.summary()).contains("渝万高铁");
        // showTime 墙钟北京时间 2026-09-26 11:45:36 → UTC 03:45:36
        assertThat(first.publishedAt()).isEqualTo(java.time.Instant.parse("2026-09-26T03:45:36Z"));
        // 同宿主快讯通道差异点：要闻频道条目自带直链字段
        assertThat(first.url())
                .isEqualTo("http://finance.eastmoney.com/news/1350,202609263884382975.html");
        // ID 游标即 externalId（code 日期前缀数值，沿 em_fastnews_7x24 同宿主口径）
        assertThat(first.cursorValue()).isEqualTo("202609263884382975");
    }
}
