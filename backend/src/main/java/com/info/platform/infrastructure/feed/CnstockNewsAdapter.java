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
 * 上证报要闻预置适配器（M17 T141，REQ-20260926-14 拍板一 #5，bean 名 {@code cnstockNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：旧 {@code news.cnstock.com} 子域 302 进新站（{@code
 * /channel/*}，卡片客户端渲染）；{@code /list/10031}
 * 上证智讯为公告流、栏目列表页无日期。现行唯一服务端渲染的新鲜窗口为<b>首页要闻卡块</b>（沿证监会首页块先例）：{@code a[class*=index_item]}
 * 锚点（CSS-module 哈希后缀，取类名前缀匹配抗构建漂移），内部 {@code h5[class*=index_title]} 标题 + {@code
 * div[class*=index_time]} 时间——相对时间（「N分钟前/N小时前」）与 {@code MM-dd} 混排，均走基类墙钟折算；轮播克隆节点同 id 重复由去重吸收。
 *
 * <p>增量口径：externalId = 详情 URL 数值段（{@code /commonDetail/{id}}）；cursorType=NONE——首页块无翻页窗口 +
 * 相对时间折算，重复轮由 (source_id, external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：cnstock.com robots 404 → 按 RFC
 * 9309「无 robots 文件 = 无限制」。频控 30min（REQ 纸媒频段上限，首页大页礼貌抓取）。
 */
@Component(CnstockNewsAdapter.BEAN_NAME)
public class CnstockNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog CNSTOCK_NEWS）。 */
    public static final String BEAN_NAME = "cnstockNewsAdapter";

    /** 详情锚点：/commonDetail/{id}（id 数值段作 externalId，页面相对链接）。 */
    private static final Pattern DETAIL_HREF = Pattern.compile("^/commonDetail/(\\d+)$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public CnstockNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    CnstockNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.cnstock.com/";
    }

    /** 解析首页要闻卡块（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> anchors = doc.select("a[class*=index_item]");
        List<RawFeedItem> items = new ArrayList<>(anchors.size());
        for (Element anchor : anchors) {
            RawFeedItem item = toItem(anchor);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "上证报要闻结构漂移（a[class*=index_item] 条目缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单卡转换：h5 标题 + index_time 墙钟 + commonDetail id 作 externalId。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher detail = DETAIL_HREF.matcher(anchor.attr("href"));
        if (!detail.matches()) {
            return null;
        }
        Element titleNode = anchor.selectFirst("[class*=index_title]");
        String title = titleNode == null ? cleanTitle(anchor.text()) : cleanTitle(titleNode.text());
        if (title == null) {
            return null;
        }
        Element timeNode = anchor.selectFirst("[class*=index_time]");
        Instant publishedAt =
                timeNode == null ? null : parseChineseWallClock(timeNode.text(), clock());
        return new RawFeedItem(
                detail.group(1), title, null, anchor.absUrl("href"), "上海证券报", publishedAt, null);
    }
}
