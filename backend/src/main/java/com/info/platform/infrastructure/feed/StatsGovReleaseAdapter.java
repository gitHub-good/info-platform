package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 统计局最新发布预置适配器（M14 T111，REQ-20260925-11 拍板一 #9，bean 名 {@code statsGovReleaseAdapter} 与目录种子 一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：{@code stats.gov.cn/sj/zxfb/}（最新发布）200 服务端渲染完整列表 （首屏 15 条）；结构
 * {@code div.list-content &gt; ul &gt; li}，每 li 含三个响应式锚点（{@code a.pc_1600} / {@code a.mhide} /
 * {@code a.pchide}，同 href 同 title）——仅取首个避免同条重复；标题在 {@code title} 属性，尾部 {@code span} 日期（{@code
 * yyyy-MM-dd} 墙钟，北京时间）。
 *
 * <p>增量口径：externalId = 解析后的绝对 URL（无显式 id，任务口径）；cursorType=NONE——日期粒度仅到日（TIME 游标 「遇已见止」漏同日新条目），重复轮由
 * (source_id, external_id) 唯一索引幂等吸收（裁量记录 ADR-0044）。robots： stats.gov.cn robots 404 → 按 RFC 9309「无
 * robots 文件 = 无限制」。频控 60min（REQ 锁定清单）。
 */
@Component(StatsGovReleaseAdapter.BEAN_NAME)
public class StatsGovReleaseAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog STATS_RELEASE）。 */
    public static final String BEAN_NAME = "statsGovReleaseAdapter";

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public StatsGovReleaseAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    StatsGovReleaseAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.stats.gov.cn/";
    }

    /** 解析列表页（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("div.list-content li");
        if (entries.isEmpty()) {
            throw new FeedFetchException(
                    "统计局最新发布结构漂移（div.list-content li 缺失）: " + source.getSourceCode());
        }
        List<RawFeedItem> items = new ArrayList<>(entries.size());
        for (Element li : entries) {
            RawFeedItem item = toItem(li);
            if (item != null) {
                items.add(item);
            }
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：首个响应式锚点（三锚同指一条）+ title 属性标题 + 绝对 URL 作 externalId + 日期墙钟。 */
    private RawFeedItem toItem(Element li) {
        Element anchor = li.selectFirst("a[href]");
        if (anchor == null) {
            return null;
        }
        String rawTitle = anchor.attr("title").isBlank() ? anchor.text() : anchor.attr("title");
        String title = cleanTitle(rawTitle);
        if (title == null) {
            return null;
        }
        String url = anchor.absUrl("href");
        if (!url.startsWith("http")) {
            return null;
        }
        Element dateSpan = li.selectFirst("span");
        Instant publishedAt =
                dateSpan == null ? null : parseChineseWallClock(dateSpan.text(), clock());
        return new RawFeedItem(url, title, null, url, "国家统计局", publishedAt, null);
    }
}
