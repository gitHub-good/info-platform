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
 * 界面新闻财经预置适配器（M17 T141，REQ-20260926-14 拍板一 #4，bean 名 {@code jiemianFinanceAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：REQ 记 {@code lists/2} 实测为<b>商业</b>频道（首页导航对照），财经频道为 {@code
 * lists/800}（100KB 服务端渲染）；主列表 {@code ul#load-list &gt; li.card-list} 卡片：标题 {@code
 * h3.card-list__title}、 摘要 {@code div.card-list__summary}、作者 {@code span.news-footer__author}、时间
 * {@code span.news-footer__date}（{@code MM/dd HH:mm} 无年份墙钟，斜杠归一后走基类 MONTH_DAY 折算——年份取时钟当年，结果晚于时钟 +1
 * 天回看上一年）。右栏快讯/专题块不在主列表内不采集。
 *
 * <p>增量口径：externalId = 详情 URL 数值段（{@code
 * /article/{id}.html}）；cursorType=NONE——列表按编辑序非时间序（卡片时间跨多日混排），重复轮由 (source_id, external_id)
 * 唯一索引幂等吸收（裁量沿 ADR-0044 21 财经先例）。robots：jiemian.com robots 200 仅禁 {@code Con} 前缀路径（REQ
 * 注记复核：列表页不涉）。频控 30min（REQ 纸媒频段上限）。
 */
@Component(JiemianFinanceAdapter.BEAN_NAME)
public class JiemianFinanceAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog JIEMIAN_FINANCE）。 */
    public static final String BEAN_NAME = "jiemianFinanceAdapter";

    /** 详情锚点：/article/{id}.html（id 数值段作 externalId）。 */
    private static final Pattern ARTICLE_HREF = Pattern.compile(".*/article/(\\d+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public JiemianFinanceAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    JiemianFinanceAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.jiemian.com/";
    }

    /** 解析财经频道主列表（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("#load-list > li.card-list");
        if (entries.isEmpty()) {
            throw new FeedFetchException(
                    "界面财经结构漂移（#load-list li.card-list 缺失）: " + source.getSourceCode());
        }
        List<RawFeedItem> items = new ArrayList<>(entries.size());
        for (Element card : entries) {
            RawFeedItem item = toItem(card);
            if (item != null) {
                items.add(item);
            }
        }
        return dedupeByExternalId(items);
    }

    /** 单卡转换：标题/摘要/作者 + article id 作 externalId + MM/dd HH:mm 墙钟（斜杠归一）。 */
    private RawFeedItem toItem(Element card) {
        Element anchor = card.selectFirst("a[href]");
        if (anchor == null) {
            return null;
        }
        Matcher article = ARTICLE_HREF.matcher(anchor.absUrl("href"));
        if (!article.matches()) {
            return null;
        }
        Element titleNode = card.selectFirst("h3.card-list__title");
        String title = titleNode == null ? cleanTitle(anchor.text()) : cleanTitle(titleNode.text());
        if (title == null) {
            return null;
        }
        Element summaryNode = card.selectFirst("div.card-list__summary");
        String summary = summaryNode == null ? null : cleanSummary(summaryNode.text());
        Element authorNode = card.selectFirst("span.news-footer__author");
        String author = authorNode == null ? null : authorNode.text().strip();
        Element dateNode = card.selectFirst("span.news-footer__date");
        Instant publishedAt =
                dateNode == null
                        ? null
                        : parseChineseWallClock(dateNode.text().replace('/', '-'), clock());
        return new RawFeedItem(
                article.group(1), title, summary, anchor.absUrl("href"), author, publishedAt, null);
    }
}
