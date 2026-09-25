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
 * 21 财经金融频道预置适配器（M14 T112，REQ-20260925-11 拍板一 #8，bean 名 {@code jingji21FinanceAdapter} 与目录种子 一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：{@code 21jingji.com/channel/finance/}（金融频道）200 服务端渲染 列表（首屏 35
 * 条）。结构 {@code div.news &gt; a}：条目直链为 m 站绝对 URL（{@code /article/{yyyymmdd}/herald/{uuid}.html}），
 * 标题在 {@code div.title h2}（与锚点 {@code title} 属性一致），摘要在 {@code div.title p}（常为空），{@code span} 为
 * 「日期或相对时间　　作者」全角分隔——首段走墙钟解析、余段作作者（空则回落站名）。时间实测两种格式：{@code yyyy-MM-dd} 与「N分钟前」（相对时间以注入 {@code
 * Clock} 折算）。
 *
 * <p>增量口径：externalId = article URL uuid 段（32 位十六进制，站内稳定标识）；cursorType=NONE——列表按编辑排序非 时间序（09-24 与
 * 09-23 交错），TIME/ID 游标「遇已见止」均会漏条目，重复轮由 (source_id, external_id) 唯一索引 幂等吸收（裁量记录 ADR-0044）。
 *
 * <p>合规注记（REQ 非功能条款，必检项留档）：robots 200——通配 {@code Allow:/}（仅禁 {@code /sitemap/generate}）， 但显式禁止
 * GPTBot/Google-Extended/anthropic-ai/ClaudeBot/CCBot/Bytespider 等 AI 训练类爬虫全站。本平台聚合展示 与本地分析（非训练）用途按
 * REQ 口径不受影响；<b>M15 AI 管道启动前复核其条款，有疑虑则该源内容不入深度分析</b>。 频控 30min（REQ 报纸频段上限）。
 */
@Component(Jingji21FinanceAdapter.BEAN_NAME)
public class Jingji21FinanceAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog JINGJI21_FINANCE）。 */
    public static final String BEAN_NAME = "jingji21FinanceAdapter";

    /** 条目直链：/article/{yyyymmdd}/{herald|normal}/{uuid}.html（uuid 作 externalId）。 */
    private static final Pattern ARTICLE_HREF =
            Pattern.compile(".*/article/\\d{8}/(?:herald|normal)/([0-9a-f]+)\\.html$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public Jingji21FinanceAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    Jingji21FinanceAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.21jingji.com/";
    }

    /** 解析金融频道列表（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<RawFeedItem> items = new ArrayList<>();
        for (Element anchor : doc.select("div.news > a")) {
            RawFeedItem item = toItem(anchor);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "21财经金融频道结构漂移（div.news > a 内 article 锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：article uuid 作 externalId + h2 标题 + span「时间　　作者」拆分。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher article = ARTICLE_HREF.matcher(anchor.attr("href"));
        if (!article.matches()) {
            return null;
        }
        Element heading = anchor.selectFirst("h2");
        String title = heading == null ? null : cleanTitle(heading.text());
        if (title == null) {
            return null;
        }
        Element paragraph = anchor.selectFirst("div.title p");
        String summary = paragraph == null ? null : cleanSummary(paragraph.text());
        Element meta = anchor.selectFirst("div.title span");
        Instant publishedAt = meta == null ? null : parseMetaTime(meta.text());
        String author = meta == null ? null : parseMetaAuthor(meta.text());
        return new RawFeedItem(
                article.group(1), title, summary, anchor.absUrl("href"), author, publishedAt, null);
    }

    /** span 首段（全角空格分隔）走墙钟解析。 */
    private Instant parseMetaTime(String raw) {
        String firstToken = raw.replace('\u3000', ' ').strip().split("\\s+")[0];
        return parseChineseWallClock(firstToken, clock());
    }

    /** span 余段作作者（多作者逗号串原样），空则回落站名。 */
    private static String parseMetaAuthor(String raw) {
        String[] tokens = raw.replace('\u3000', ' ').strip().split("\\s+");
        if (tokens.length < 2) {
            return "21财经";
        }
        StringBuilder author = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].isBlank()) {
                author.append(tokens[i]);
            }
        }
        return author.isEmpty() ? "21财经" : author.toString();
    }
}
