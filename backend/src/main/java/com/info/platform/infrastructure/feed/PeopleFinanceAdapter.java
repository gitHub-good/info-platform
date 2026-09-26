package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * 人民网经济预置适配器（M17 T142，REQ-20260926-14 拍板一 #7，bean 名 {@code peopleFinanceAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：端点 {@code finance.people.com.cn} 频道首页（94KB 服务端渲染）； 文章锚点统一为
 * {@code /n1/yyyy/mmdd/c{栏目id}-{文章id}.html} 形态（首页要闻/头条/栏目区块混排均此形态，专题与旧栏目走 {@code /GB/} 旧路径——按
 * 形态过滤天然隔离）；列表无显式时间展示，发布时间取 <b>URL 内嵌日期</b>（日粒度，北京零点——沿发改委日粒度墙钟取舍）。
 *
 * <p>增量口径：externalId = URL 尾段文章 id（{@code c1004-40805164} 的 40805164）；cursorType=NONE——日粒度 URL 日期 +
 * 首页编辑序，重复轮由 (source_id, external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：{@code www.people.com.cn}
 * robots 200 为 {@code User-agent: * Disallow:（空 = 全放行） + Crawl-delay: 120}（{@code finance.} 子域 robots 404
 * 无独立限制）——间隔 60min = Crawl-delay 的 30 倍裕量（REQ 要求 ≥15 倍）。
 */
@Component(PeopleFinanceAdapter.BEAN_NAME)
public class PeopleFinanceAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog PEOPLE_FINANCE）。 */
    public static final String BEAN_NAME = "peopleFinanceAdapter";

    /** 文章锚点：/n1/yyyy/mmdd/c{栏目}-{id}.html（id 数值段作 externalId，mmdd 为 4 位月日）。 */
    private static final Pattern ARTICLE_HREF =
            Pattern.compile("^.*/n1/(\\d{4})/(\\d{2})(\\d{2})/c\\d+-(\\d+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public PeopleFinanceAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    PeopleFinanceAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "http://www.people.com.cn/";
    }

    /** 解析频道首页 /n1/ 文章锚点（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<RawFeedItem> items = new ArrayList<>();
        for (Element anchor : doc.select("a[href]")) {
            RawFeedItem item = toItem(anchor);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "人民网经济结构漂移（/n1/ 文章锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题 + /n1/ 形态锚定 + URL 内嵌日期墙钟（北京零点，日粒度）。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher article = ARTICLE_HREF.matcher(anchor.absUrl("href"));
        if (!article.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        LocalDate date =
                LocalDate.of(
                        Integer.parseInt(article.group(1)),
                        Integer.parseInt(article.group(2)),
                        Integer.parseInt(article.group(3)));
        Instant publishedAt = date.atStartOfDay(SHANGHAI).toInstant();
        return new RawFeedItem(
                article.group(4),
                title,
                null,
                anchor.absUrl("href"),
                "人民网",
                publishedAt,
                null);
    }
}
