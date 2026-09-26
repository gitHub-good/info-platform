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
 * 金融界首页预置适配器（M18 T150，REQ-20260926-15 拍板一 #6，bean 名 {@code jrjHomeAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：REQ 记「列表路径」实测 {@code list/financenews.shtml} 404、 {@code
 * finance.jrj.com.cn} 与 {@code list/important_news.shtml} 均为导航壳/静态精选（滞后 12 天）——现行服务端渲染新鲜窗口为 <b>www
 * 根首页</b>（81 条带题锚点，跨 finance/stock/bank 等频道子域）；文章 URL 形态 {@code {频道}.jrj.com.cn/yyyy/MM/ddHHmm{8 位
 * id}.shtml}（URL 内嵌分钟精度墙钟）。
 *
 * <p><b>合规注记（REQ 场景 6 必检项落地）</b>：robots（301 跟随 https 后 200）{@code User-agent: *}
 * 仅禁搜索/翻页<b>参数</b>路径（{@code ?page=}/{@code ?keyword=}/{@code /search} 等）——www
 * 根为无参数路径，允许抓取；引擎永不构造参数翻页（首页即窗口）。
 *
 * <p>增量口径：externalId = URL 尾号 8 位 id；cursorType=NONE——首页编辑序 + 分钟粒度墙钟（沿 cnstock 裁量，ADR-0044）， 重复轮由
 * (source_id, external_id) 唯一索引幂等吸收。频控 15min（REQ 门户频段下限）。
 */
@Component(JrjHomeAdapter.BEAN_NAME)
public class JrjHomeAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog JRJ_HOME）。 */
    public static final String BEAN_NAME = "jrjHomeAdapter";

    /** 文章锚点：{频道}.jrj.com.cn/yyyy/MM/ddHHmm{id}.shtml——日段 dd 与 HHmm 相邻（组 3~5），尾号 id 作 externalId。 */
    private static final Pattern ARTICLE_HREF =
            Pattern.compile(
                    "^https?://[a-z]+\\.jrj\\.com\\.cn/(\\d{4})/(\\d{2})/(\\d{2})(\\d{2})(\\d{2})(\\d{8,})\\.shtml$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public JrjHomeAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    JrjHomeAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.jrj.com.cn/";
    }

    /** 解析 www 根首页文章锚点（包内可见，fixture 单测直调零外呼）。 */
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
                    "金融界结构漂移（yyyy/MM/ddHHmm 文章锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本标题 + URL 内嵌 ddHHmm 分钟墙钟（Asia/Shanghai）+ 尾号 id externalId。 */
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
        LocalTime time =
                LocalTime.of(
                        Integer.parseInt(article.group(4)), Integer.parseInt(article.group(5)));
        Instant publishedAt = date.atTime(time).atZone(SHANGHAI).toInstant();
        return new RawFeedItem(
                article.group(6), title, null, anchor.absUrl("href"), "金融界", publishedAt, null);
    }
}
