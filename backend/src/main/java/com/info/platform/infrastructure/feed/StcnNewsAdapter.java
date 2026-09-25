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
 * 证券时报要闻预置适配器（M14 T112，REQ-20260925-11 拍板一 #6，bean 名 {@code stcnNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：普查记旧快讯路径 {@code /article/list/kuaixun.html} 与 RSS 均 404；
 * 现行栏目入口由首页 {@code data-items} 标签对照得出 {@code /article/list/yw.html}（要闻栏，55KB 服务端渲染，首屏 10 条 + {@code
 * data-url} 无限滚动后续页不入本适配器窗口）。结构 {@code ul.list &gt; li &gt; div.content}：标题在 {@code div.tt
 * a}（{@code /article/detail/{id}.html} 相对链接），摘要在 {@code div.text a}，{@code div.info} 内 span 序列为
 * [媒体名, (作者), 时间]——首 span 作 author、末 span 作时间（{@code HH:mm} 当天墙钟，零点后抓昨夜条目回看前一天）。
 *
 * <p>增量口径：externalId = 详情 id（{@code /article/detail/{id}.html} 数值段）；cursorType=NONE——首屏 id 序列实测
 * 存在相邻乱序对（4199183 先于 4199184），ID 游标「遇已见止」会漏排序列靠后的新条目，重复轮由 (source_id, external_id) 唯一索引幂等吸收（裁量记录
 * ADR-0044）。robots：stcn.com robots 为自定义 404 页 → 按「无 robots 文件 = 无限制」。频控 30min（REQ 报纸频段 15~30 上限）。
 */
@Component(StcnNewsAdapter.BEAN_NAME)
public class StcnNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog STCN_NEWS）。 */
    public static final String BEAN_NAME = "stcnNewsAdapter";

    /** 详情锚点：/article/detail/{id}.html（id 数值段作 externalId）。 */
    private static final Pattern DETAIL_HREF = Pattern.compile("^/article/detail/(\\d+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public StcnNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    StcnNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.stcn.com/";
    }

    /** 解析要闻列表（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("ul.list li");
        if (entries.isEmpty()) {
            throw new FeedFetchException("证券时报要闻结构漂移（ul.list li 缺失）: " + source.getSourceCode());
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

    /** 单条转换：div.tt 锚点标题 + 详情 id 作 externalId + info 首 span 媒体名 + 末 span 当天时间。 */
    private RawFeedItem toItem(Element li) {
        Element anchor = li.selectFirst("div.tt a[href]");
        if (anchor == null) {
            return null;
        }
        Matcher detail = DETAIL_HREF.matcher(anchor.attr("href"));
        if (!detail.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        Element summaryAnchor = li.selectFirst("div.text a");
        String summary = summaryAnchor == null ? null : cleanSummary(summaryAnchor.text());
        var infoSpans = li.select("div.info span");
        String author =
                infoSpans.isEmpty() ? "证券时报" : cleanTextOrFallback(infoSpans.first(), "证券时报");
        Instant publishedAt =
                infoSpans.isEmpty()
                        ? null
                        : parseChineseWallClock(infoSpans.last().text(), clock());
        return new RawFeedItem(
                detail.group(1), title, summary, anchor.absUrl("href"), author, publishedAt, null);
    }

    private static String cleanTextOrFallback(Element span, String fallback) {
        String text = span.text().strip();
        return text.isEmpty() ? fallback : text;
    }
}
