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
 * 凤凰财经首页预置适配器（M18 T150，REQ-20260926-15 拍板一 #2，bean 名 {@code ifengFinanceAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code finance.ifeng.com} 首页（151KB 服务端渲染；旧列表 api 已弃用，REQ
 * 注记走 HTML）；文章锚点为现行短链形态 {@code https://finance.ifeng.com/c/{base62 码}}（首页 74
 * 条带题），专题/视频锚点（v./original. 子域与 special 路径）不匹配形态天然隔离。
 *
 * <p>增量口径：externalId = 短链 base62 码；cursorType=NONE——首页<b>无显式时间展示</b>，发布时间回落抓取时刻（摄取层口径）， 重复轮由
 * (source_id, external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：ifeng robots 200 全放行（ 附 llms.txt
 * 指引，聚合展示不涉）。 频控 15min（REQ 门户频段下限）。
 */
@Component(IfengFinanceAdapter.BEAN_NAME)
public class IfengFinanceAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog IFENG_FINANCE）。 */
    public static final String BEAN_NAME = "ifengFinanceAdapter";

    /** 文章短链锚点：https://finance.ifeng.com/c/{base62 码}（绝对形态，同站 original/v 子域不涉）。 */
    private static final Pattern SHORT_LINK_HREF =
            Pattern.compile("^https?://finance\\.ifeng\\.com/c/([A-Za-z0-9]+)$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public IfengFinanceAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    IfengFinanceAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.ifeng.com/";
    }

    /** 解析首页 /c/ 短链锚点（包内可见，fixture 单测直调零外呼）。 */
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
                    "凤凰财经结构漂移（finance.ifeng.com/c/ 短链锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题 + base62 码 externalId + 无显式时间（发布时间回落摄取层）。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher shortLink = SHORT_LINK_HREF.matcher(anchor.attr("href").trim());
        if (!shortLink.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        return new RawFeedItem(
                shortLink.group(1), title, null, anchor.absUrl("href"), "凤凰财经", null, null);
    }
}
