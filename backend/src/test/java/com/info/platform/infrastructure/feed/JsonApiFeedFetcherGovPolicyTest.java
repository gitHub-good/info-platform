package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * gov_policy 源 JSON_API 通道解析单测（V2.3-M23 T200，ADR-0062 裁决一）：真实截样本（2026-09-22 外呼， ZUIXINZHENGCE.json
 * 根数组 TITLE/URL/DOCRELPUBTIME 按时间倒序，全量 1100 条）零外呼回放—— 验证纯目录配置对 gov.cn JSON 的解析出数：TITLE
 * 出题、URL→url+externalId（同源去重锚）、DOCRELPUBTIME 纯日期 → 上海零点 UTC、 cursorType=NONE 全量产出（重复轮由 (source_id,
 * external_id) 唯一索引吸收）。
 */
class JsonApiFeedFetcherGovPolicyTest {

    private final JsonApiFeedFetcher fetcher = new JsonApiFeedFetcher(RestClient.builder().build());

    @Test
    void parse_govPolicyZuixinzhengce_rootArrayMappedWithDateOnlyNormalized() {
        InfoSource source = PresetSources.fromCode("gov_policy");

        List<RawFeedItem> items =
                fetcher.parse(
                                FeedFixtures.load("feed/gov-policy-zuixinzhengce-sample.json"),
                                source,
                                FetchContext.firstPage(null))
                        .items();

        // 截样本 5 条全量产出（无游标止步：cursorType=NONE）
        assertThat(items).hasSize(5);
        RawFeedItem first = items.get(0);
        assertThat(first.title()).isEqualTo("中华人民共和国审计法实施条例");
        assertThat(first.url())
                .isEqualTo("https://www.gov.cn/zhengce/content/202609/content_7081972.htm");
        // externalId=URL：policy_item existsBySourceUrl 去重语义迁移落点
        assertThat(first.externalId()).isEqualTo(first.url());
        // DOCRELPUBTIME=2026-09-24（纯日期）→ 上海当日 00:00 → UTC 前一日 16:00
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-23T16:00:00Z"));
        // gov.cn 列表无摘要字段：summary 不产出（title 非空不走标题回落）
        assertThat(first.summary()).isNull();

        // 时间倒序保真（截样本首条即最新；末条 2026-09-17 → UTC 09-16 16:00）
        RawFeedItem last = items.get(4);
        assertThat(last.publishedAt()).isEqualTo(Instant.parse("2026-09-16T16:00:00Z"));
        assertThat(last.externalId())
                .isEqualTo("https://www.gov.cn/zhengce/content/202609/content_7081356.htm");
    }
}
