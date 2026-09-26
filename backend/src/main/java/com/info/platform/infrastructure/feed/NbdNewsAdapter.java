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
 * 每日经济新闻首页预置适配器（M18 T151，REQ-20260926-15 拍板一 #3，bean 名 {@code nbdNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code www.nbd.com.cn} 首页（178KB 服务端渲染，159 条唯一文章锚点）； 文章 URL
 * 形态 {@code /articles/{yyyy-MM-dd}/{id}.html}（URL 内嵌日粒度墙钟）；无题缩略锚点由「标题非空」隔离。
 *
 * <p>增量口径：externalId = URL 尾号 id；cursorType=NONE——URL 日期仅日粒度（沿人民网日粒度取舍，北京零点），重复轮由 (source_id,
 * external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：nbd robots 200 仅禁 js/css/console 与查询参数路径（文章列表不涉）。 频控
 * 15min（REQ 媒体频段下限）。
 */
@Component(NbdNewsAdapter.BEAN_NAME)
public class NbdNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog NBD_NEWS）。 */
    public static final String BEAN_NAME = "nbdNewsAdapter";

    /** 文章锚点：/articles/{yyyy-MM-dd}/{id}.html（日期段作日粒度墙钟，id 作 externalId）。 */
    private static final Pattern ARTICLE_HREF =
            Pattern.compile(
                    "^(?:https?://www\\.nbd\\.com\\.cn)?/articles/(\\d{4})-(\\d{2})-(\\d{2})/(\\d+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public NbdNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    NbdNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.nbd.com.cn/";
    }

    /** 解析首页 /articles/ 锚点（包内可见，fixture 单测直调零外呼）。 */
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
                    "每日经济新闻结构漂移（/articles/ 文章锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题 + URL 内嵌日期墙钟（日粒度北京零点）+ 尾号 id externalId。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher article = ARTICLE_HREF.matcher(anchor.attr("href").trim());
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
                article.group(4), title, null, anchor.absUrl("href"), "每日经济新闻", publishedAt, null);
    }
}
