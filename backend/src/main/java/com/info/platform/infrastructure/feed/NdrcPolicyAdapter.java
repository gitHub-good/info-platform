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
 * 发改委政策发布预置适配器（M14 T111，REQ-20260925-11 拍板一 #4，bean 名 {@code ndrcPolicyAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：普查路径 {@code /xxgk/zcfb/fzggwl/}（政策发布·发展改革委令）现为 200 完整列表页（普查记
 * 4339B 系父路径 JS 跳转中间态）；结构 {@code div.list &gt; ul.u-list &gt; li}，条目 = 锚点 （{@code title}
 * 属性为标题，{@code ./202609/t….html} 相对链接）+ 尾部 {@code span} 日期（{@code yyyy/MM/dd} 墙钟，
 * 北京时间）。列表内混排「相关解读」跨栏条目（{@code ../../jd/jd/} 链接）——政策解读与政策同源同栏呈现，按栏内内容保留。
 *
 * <p>增量口径：externalId = 解析后的绝对 URL（任务口径「列表页无显式 id 时 URL 作 externalId」）；cursorType=NONE——
 * 日期粒度仅到日，TIME 游标「遇已见止」会漏同日新条目（裁量记录 ADR-0044），重复轮由 (source_id, external_id) 唯一索引
 * 幂等吸收（日级低频源量级可忽略）。robots：ndrc.gov.cn robots 本身 403（WAF 拒读）→ 按 RFC 9309「无 robots 文件 =
 * 无限制」口径处理并留档（REQ 场景 5 必检项落地复核）。频控 60min（REQ 频段 30~60 上限，日级源礼貌抓取）。
 */
@Component(NdrcPolicyAdapter.BEAN_NAME)
public class NdrcPolicyAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog NDRC_POLICY）。 */
    public static final String BEAN_NAME = "ndrcPolicyAdapter";

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public NdrcPolicyAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    NdrcPolicyAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.ndrc.gov.cn/";
    }

    /** 解析列表页（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("div.list ul.u-list li");
        if (entries.isEmpty()) {
            throw new FeedFetchException(
                    "发改委政策发布结构漂移（div.list ul.u-list li 缺失）: " + source.getSourceCode());
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

    /** 单条转换：锚点标题（title 属性优先）+ 绝对化 URL（作 externalId 与直链）+ span 日期墙钟。 */
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
        Instant publishedAt = dateSpan == null ? null : parseWallClock(dateSpan);
        return new RawFeedItem(url, title, null, url, "国家发展改革委", publishedAt, null);
    }

    private Instant parseWallClock(Element dateSpan) {
        return parseChineseWallClock(dateSpan.text(), clock());
    }
}
