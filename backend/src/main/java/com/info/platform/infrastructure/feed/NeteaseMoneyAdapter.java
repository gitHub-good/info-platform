package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
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
 * 网易财经首页预置适配器（M18 T150，REQ-20260926-15 拍板一 #1，bean 名 {@code neteaseMoneyAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code money.163.com} 首页（195KB 服务端渲染）； 文章锚点为网易号动态文章 {@code
 * /dy/article/{大写码}.html} 形态（首页要闻/滚动块混排均此形态）；图片克隆锚点无题文本由「标题非空」隔离，同码重复由去重吸收。
 *
 * <p>增量口径：externalId = 文章码（{@code L7N9737F05198NMR}）；cursorType=NONE——首页列表<b>无显式时间展示</b>，发布时间回落抓取时刻
 * （摄取层口径，沿网易无墙钟取舍），重复轮由 (source_id, external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。 robots：money.163.com
 * robots 200 {@code User-agent: * Disallow:（空 = 全放行）}。频控 15min（REQ 门户频段下限，高更新密度）。
 */
@Component(NeteaseMoneyAdapter.BEAN_NAME)
public class NeteaseMoneyAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog NETEASE_MONEY）。 */
    public static final String BEAN_NAME = "neteaseMoneyAdapter";

    /** 网易号动态文章锚点：…/dy/article/{大写字母数字码}.html。 */
    private static final Pattern DY_ARTICLE_HREF =
            Pattern.compile(
                    "^(?:https?://(?:www|money)\\.163\\.com)?/dy/article/([A-Z0-9]+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public NeteaseMoneyAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    NeteaseMoneyAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.163.com/";
    }

    /** 解析首页 /dy/article/ 锚点（包内可见，fixture 单测直调零外呼）。 */
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
            throw new FeedFetchException("网易财经结构漂移（/dy/article/ 锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题（无题图片锚点隔离）+ 文章码 externalId + 无显式时间（发布时间回落摄取层）。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher article = DY_ARTICLE_HREF.matcher(anchor.attr("href"));
        if (!article.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        return new RawFeedItem(
                article.group(1), title, null, anchor.absUrl("href"), "网易财经", null, null);
    }
}
