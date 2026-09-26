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
 * 财政部政策发布预置适配器（M17 T140，REQ-20260926-14 拍板一 #2，bean 名 {@code mofPolicyAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-22 预检实测留档，ADR-0053）：端点 {@code szs.mof.gov.cn/zhengcefabu/}（税政司·政策发布， REQ
 * 记路径即现行入口，12KB 服务端渲染）；结构 {@code div.listBox &gt; ul.liBox &gt; li}，条目 = 锚点（{@code title}
 * 属性为标题，{@code ./202609/t20260904_3996707.htm} 相对链接）+ 尾部 {@code span} 日期（{@code yyyy-MM-dd} 墙钟，北京时间，
 * 日粒度）。首屏 10 条。
 *
 * <p>增量口径：externalId = 详情 URL 尾号数值段（{@code t{date}_{id}.htm} 的 id）；cursorType=NONE——日期粒度仅到日， TIME 游标「遇已见止」会漏同日新条目（裁量沿 ADR-0044
 * 发改委先例），重复轮由 (source_id, external_id) 唯一索引幂等吸收。robots： szs.mof.gov.cn robots 302 跳主站 404 页（非 robots
 * 文件）→ 按「无 robots 文件 = 无限制」留档（沿证监会 302 先例）。频控 60min（REQ 官方频段上限）。
 */
@Component(MofPolicyAdapter.BEAN_NAME)
public class MofPolicyAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog MOF_POLICY）。 */
    public static final String BEAN_NAME = "mofPolicyAdapter";

    /** 详情链接：…/t{yyyyMMdd}_{id}.htm（id 数值段作 externalId，.htm/.html 兼容）。 */
    private static final Pattern DETAIL_ID = Pattern.compile("t\\d{8}_(\\d+)\\.html?$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public MofPolicyAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    MofPolicyAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://szs.mof.gov.cn/";
    }

    /** 解析政策发布列表（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<Element> entries = doc.select("div.listBox ul.liBox li");
        if (entries.isEmpty()) {
            throw new FeedFetchException(
                    "财政部政策发布结构漂移（div.listBox ul.liBox li 缺失）: " + source.getSourceCode());
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

    /** 单条转换：锚点标题（title 属性优先）+ 尾号 id 作 externalId + span 日期墙钟。 */
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
        Matcher detail = DETAIL_ID.matcher(url);
        if (!detail.find()) {
            return null;
        }
        Element dateSpan = li.selectFirst("span");
        Instant publishedAt = dateSpan == null ? null : parseWallClock(dateSpan);
        return new RawFeedItem(detail.group(1), title, null, url, "财政部", publishedAt, null);
    }

    private Instant parseWallClock(Element dateSpan) {
        return parseChineseWallClock(dateSpan.text(), clock());
    }
}
