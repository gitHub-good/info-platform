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
 * 证监会要闻预置适配器（M14 T111，REQ-20260925-11 拍板一 #5，bean 名 {@code csrcNewsAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-25 预检实测留档，ADR-0044）：普查端点 {@code /csrc/c100028/common_list.shtml} 实测返回<b>冻结
 * 快照</b>（页面生成时间 2021-12-12，换 UA/Accept 复测一致，16172B 与普查字节相同）；现行列表页变体 {@code common_xq_list.shtml}
 * 服务端渲染为空 {@code ul#list}（客户端填充，无可见数据端点）。现行结构对照结论：取<b>首页要闻 tab 块</b>（服务端渲染、2026-09 当日新鲜）——{@code
 * li.li-height &gt; a + span.time}，置顶 {@code li.first} 为跨栏 置顶（c106311 领导活动），以 {@code
 * /csrc/c100028/c\u005cd+/content.shtml} href 锚定排除非本栏条目。
 *
 * <p>时间口径：{@code span.time} 为 {@code MM-dd}（无年份）——按抓取时钟当年补全（跨年由「结果晚于时钟 +1 天回看上一年」
 * 防未来时刻），日粒度取北京零点。externalId = 锚点路径内的内容 id（{@code c} 前缀）；cursorType=NONE（裁量记录 ADR-0044）。robots：302
 * 跳 HTML 页（非 robots 文件）→ 按「无 robots 文件 = 无限制」处理并留档。频控 60min （REQ 频段上限，首页 221KB 大页礼貌抓取）。
 */
@Component(CsrcNewsAdapter.BEAN_NAME)
public class CsrcNewsAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog CSRC_NEWS）。 */
    public static final String BEAN_NAME = "csrcNewsAdapter";

    /** 要闻栏（c100028）内容锚点：/csrc/c100028/c{id}/content.shtml。 */
    private static final Pattern HEADLINE_HREF =
            Pattern.compile("^/csrc/c100028/c(\\d+)/content\\.shtml$");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public CsrcNewsAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    CsrcNewsAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "https://www.csrc.gov.cn/";
    }

    /** 解析首页要闻块（包内可见，fixture 单测直调零外呼）。 */
    @Override
    List<RawFeedItem> parseList(String body, InfoSource source) {
        Document doc = Jsoup.parse(body, source.getEndpoint());
        List<RawFeedItem> items = new ArrayList<>();
        for (Element li : doc.select("li.li-height")) {
            RawFeedItem item = toItem(li);
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new FeedFetchException(
                    "证监会要闻结构漂移（li.li-height 内 c100028 锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：c100028 href 锚定（置顶跨栏条目排除）+ MM-dd 补当年 + 内容 id 作 externalId。 */
    private RawFeedItem toItem(Element li) {
        Element anchor = li.selectFirst("a[href]");
        if (anchor == null) {
            return null;
        }
        Matcher href = HEADLINE_HREF.matcher(anchor.attr("href"));
        if (!href.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        Element time = li.selectFirst("span.time");
        Instant publishedAt = time == null ? null : parseChineseWallClock(time.text(), clock());
        return new RawFeedItem(
                "c" + href.group(1),
                title,
                null,
                anchor.absUrl("href"),
                "中国证监会",
                publishedAt,
                null);
    }
}
