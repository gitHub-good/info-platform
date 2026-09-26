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
 * 中国经济网滚动新闻预置适配器（M18 T152 条件席〔第 9 席〕，REQ-20260926-15 补位序第 2 位顶替——和讯瑞数盾 FAIL，bean 名 {@code
 * ceNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code www.ce.cn} 首页（105KB 服务端渲染，182 条带题锚点跨生活/城市等子站）；
 * 限定「滚动新闻」栏目路径 {@code /xwzx/gnsz/gdxw/{yyyymm}/t{yyyymmdd}_{id}.shtml}（41 条/页，语义精确且生活类锚点天然隔离， 沿中证网
 * 7×24 块先例）——URL 内嵌 t 日期<b>日粒度</b>墙钟（北京零点，沿人民网取舍）。
 *
 * <p>增量口径：externalId = URL 尾号 id；cursorType=NONE——日粒度墙钟 + 编辑序，重复轮由 (source_id, external_id)
 * 唯一索引幂等吸收 （裁量沿 ADR-0044）。robots：ce.cn robots 200 {@code User-agent: * Allow:/
 * Disallow:/guanggao/}（REQ 记 301 循环已落地 失效）。 频控 30min（首页大页礼貌抓取，滚动栏目小时级更新密度）。
 */
@Component(CeNewsAdapter.BEAN_NAME)
public class CeNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog CE_NEWS）。 */
    public static final String BEAN_NAME = "ceNewsAdapter";

    /** 滚动新闻锚点：/xwzx/gnsz/gdxw/{yyyymm}/t{yyyymmdd}_{id}.shtml（t 日期段作墙钟，id 作 externalId）。 */
    private static final Pattern ROLL_HREF =
            Pattern.compile(
                    "^(?:https?://www\\.ce\\.cn)?/xwzx/gnsz/gdxw/\\d{6}/t(\\d{4})(\\d{2})(\\d{2})_(\\d+)\\.shtml$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public CeNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    CeNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "http://www.ce.cn/";
    }

    /** 解析首页滚动新闻块锚点（包内可见，fixture 单测直调零外呼）。 */
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
            throw new FeedFetchException("中国经济网结构漂移（gdxw 滚动新闻锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题 + t 日期墙钟（日粒度北京零点）+ 尾号 id externalId。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher roll = ROLL_HREF.matcher(anchor.attr("href").trim());
        if (!roll.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        LocalDate date =
                LocalDate.of(
                        Integer.parseInt(roll.group(1)),
                        Integer.parseInt(roll.group(2)),
                        Integer.parseInt(roll.group(3)));
        Instant publishedAt = date.atStartOfDay(SHANGHAI).toInstant();
        return new RawFeedItem(
                roll.group(4), title, null, anchor.absUrl("href"), "中国经济网", publishedAt, null);
    }
}
