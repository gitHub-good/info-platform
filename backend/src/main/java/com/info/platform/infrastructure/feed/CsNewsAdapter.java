package com.info.platform.infrastructure.feed;

import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * 中证网要闻预置适配器（M17 T141，REQ-20260926-14 拍板一 #6，bean 名 {@code csNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：栏目列表页（{@code /sylm/jsbd/list.html}、{@code /gppd/} 系栏目 list 页
 * 等）均为 JS 模板渲染（{@code #title#} 占位符），首页为唯一服务端渲染窗口——取首页<b>中证快讯 7×24 块</b>：{@code li &gt; em{HH:mm} +
 * a}，锚点 {@code title} 属性为标题、链接内嵌完整日期（{@code /ssgs/01/yyyy/MM/dd/detail_{id}.html}）；发布时间 = URL 日期 + em 时分（北京墙钟精确到分，优于日粒度）。
 * 其余首页区块（推荐旧文等）无 em 时分不在采集面。
 *
 * <p>增量口径：externalId = 详情 URL {@code detail_{id}} 尾号数值段（日期前缀天然携带，同日不同条唯一）；cursorType=NONE—— 首页块无翻页窗口，重复轮由
 * (source_id, external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：cs.com.cn robots 404 → 按 RFC 9309「无 robots
 * 文件 = 无限制」。频控 30min（REQ 纸媒频段上限）。同质对冲：与证券时报同稿由跨源指纹去重前置拦截（REQ 场景 5）。
 */
@Component(CsNewsAdapter.BEAN_NAME)
public class CsNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog CS_NEWS）。 */
    public static final String BEAN_NAME = "csNewsAdapter";

    /** 详情链接：/栏目/…/yyyy/MM/dd/detail_{id}.html（id 数值段作 externalId，URL 日期拼合 em 时分）。 */
    private static final Pattern DETAIL_HREF =
            Pattern.compile("^.*/(\\d{4})/(\\d{2})/(\\d{2})/detail_(\\d+)\\.html$");

    /** em 时分：HH:mm（当日墙钟，与 URL 日期拼合）。 */
    private static final Pattern EM_TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public CsNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    CsNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.cs.com.cn/";
    }

    /** 解析首页 7×24 块（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<RawFeedItem> items = new ArrayList<>();
        for (Element li : doc.select("li")) {
            RawFeedItem item = toItem(li);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "中证网要闻结构漂移（li>em+a 快讯条目缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：li 内 em 时分 + a 详情链接（title 属性优先）+ URL 日期拼合墙钟。 */
    private RawFeedItem toItem(Element li) {
        Element timeNode = li.selectFirst("> em");
        Element anchor = li.selectFirst("> a[href]");
        if (timeNode == null || anchor == null) {
            return null;
        }
        Matcher time = EM_TIME.matcher(timeNode.text().strip());
        Matcher detail = DETAIL_HREF.matcher(anchor.absUrl("href"));
        if (!time.matches() || !detail.matches()) {
            return null;
        }
        String rawTitle =
                anchor.attr("title").isBlank() ? anchor.text() : anchor.attr("title");
        String title = cleanTitle(rawTitle);
        if (title == null) {
            return null;
        }
        LocalDate date = LocalDate.of(
                Integer.parseInt(detail.group(1)),
                Integer.parseInt(detail.group(2)),
                Integer.parseInt(detail.group(3)));
        Instant publishedAt =
                date.atTime(LocalTime.of(hour(time.group(1)), minute(time.group(2))))
                        .atZone(SHANGHAI)
                        .toInstant();
        return new RawFeedItem(
                detail.group(4), title, null, anchor.absUrl("href"), "中证网", publishedAt, null);
    }

    private static int hour(String raw) {
        return Integer.parseInt(raw);
    }

    private static int minute(String raw) {
        return Integer.parseInt(raw);
    }
}
