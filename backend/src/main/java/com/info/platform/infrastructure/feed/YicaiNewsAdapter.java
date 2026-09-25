package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 第一财经资讯预置适配器（M14 T112，REQ-20260925-11 拍板一 #7，bean 名 {@code yicaiNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：普查记旧快讯 API {@code /api/ajax/getInstantNews*} 已死（跳 404）； 现行入口
 * {@code yicai.com/news/}（396KB 服务端渲染主列表）。结构 {@code div#newslist &gt; a.f-db}：条目直链 {@code
 * /news/{id}.html}（数值 id 单调分配），标题在 {@code div.common h2}，摘要在 {@code div.common p}，时间在 {@code
 * div.author span}——实测格式「N小时前」「昨天 HH:mm」（绝对日期格式为主列表外区块所有，解析器一并支持）。
 *
 * <p>增量口径：externalId = news 数值 id；cursorType=NONE——主列表尾部实测存在相邻乱序对（103378038 先于 103378039），ID
 * 游标「遇已见止」会漏排序列靠后的新条目，重复轮由 (source_id, external_id) 唯一索引幂等吸收（裁量 记录 ADR-0044）。相对时间以注入 {@code Clock}
 * 折算（抓取即时语义），单测固定时钟可重复。robots：yicai.com robots 200 仅禁 {@code /api/}、{@code /search}——本源走 {@code
 * /news/} HTML 列表不受影响。频控 20min（REQ 报纸 频段 15~30 中值，快讯密度较高的列表源）。
 */
@Component(YicaiNewsAdapter.BEAN_NAME)
public class YicaiNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog YICAI_NEWS）。 */
    public static final String BEAN_NAME = "yicaiNewsAdapter";

    /** 条目直链：/news/{id}.html（数值 id 作 externalId）。 */
    private static final Pattern NEWS_HREF = Pattern.compile("^/news/(\\d+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public YicaiNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    YicaiNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.yicai.com/";
    }

    /** 解析主列表（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("#newslist > a");
        if (entries.isEmpty()) {
            throw new FeedFetchException("第一财经资讯结构漂移（#newslist > a 缺失）: " + source.getSourceCode());
        }
        List<RawFeedItem> items = new ArrayList<>(entries.size());
        for (Element anchor : entries) {
            RawFeedItem item = toItem(anchor);
            if (item != null) {
                items.add(item);
            }
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：h2 标题 + p 摘要 + author 区内首个可识别时间文本（相对时间以时钟折算）。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher news = NEWS_HREF.matcher(anchor.attr("href"));
        if (!news.matches()) {
            return null;
        }
        Element heading = anchor.selectFirst("h2");
        String title = heading == null ? null : cleanTitle(heading.text());
        if (title == null) {
            return null;
        }
        Element paragraph = anchor.selectFirst("div.common p");
        String summary = paragraph == null ? null : cleanSummary(paragraph.text());
        return new RawFeedItem(
                news.group(1),
                title,
                summary,
                anchor.absUrl("href"),
                "第一财经",
                firstParsedTime(anchor),
                null);
    }

    /** author 区 span 逐个尝试墙钟解析，首个可识别者为准（leftspan 空位跳过）。 */
    private Instant firstParsedTime(Element anchor) {
        for (Element span : anchor.select("div.author span")) {
            Instant parsed = parseChineseWallClock(span.text(), clock());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }
}
