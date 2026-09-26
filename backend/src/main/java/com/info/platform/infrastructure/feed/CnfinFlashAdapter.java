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
 * 新华财经首页快讯块预置适配器（M18 T151，REQ-20260926-15 拍板一 #4，bean 名 {@code cnfinFlashAdapter} 与目录种子一致）。
 *
 * <p>栏目口径（2026-09-26 预检实测留档，ADR-0055）：端点 {@code www.cnfin.com} 首页（200KB 服务端渲染，外链合作站点多）； 唯一新鲜 SSR
 * 窗口为首页「新华快讯」块——锚点形态 {@code //www.cnfin.com/kx/detail/{yyyymmdd}/{id}_1.html}（协议相对，15 条全带题）， 锚点文本
 * 内嵌 {@code HH:mm} 时分前缀（与 URL 日期拼合<b>分钟精度</b>墙钟，沿中证网 7×24 块先例 ADR-0053）。
 *
 * <p>增量口径：externalId = 快讯 id（{@code 4475194}）；cursorType=NONE——首页块无翻页窗口 + 编辑序，重复轮由 (source_id,
 * external_id) 唯一索引幂等吸收（裁量沿 ADR-0044）。robots：cnfin robots（301 跟随 https 后 200）{@code User-agent: *
 * Disallow:（空 = 全放行）}。频控 15min（REQ 媒体频段下限，快讯密度较高）。
 */
@Component(CnfinFlashAdapter.BEAN_NAME)
public class CnfinFlashAdapter extends AbstractHtmlListFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog CNFIN_FLASH）。 */
    public static final String BEAN_NAME = "cnfinFlashAdapter";

    /** 快讯锚点：//www.cnfin.com/kx/detail/{yyyymmdd}/{id}_1.html（日期段拼合墙钟，id 作 externalId）。 */
    private static final Pattern FLASH_HREF =
            Pattern.compile(
                    "^(?:https?:)?//www\\.cnfin\\.com/kx/detail/(\\d{4})(\\d{2})(\\d{2})/(\\d+)_1\\.html$");

    /** 锚点文本时分前缀：HH:mm（剥离后成题；缺前缀回落 URL 日期北京零点）。 */
    private static final Pattern TIME_PREFIX = Pattern.compile("^(\\d{1,2}):(\\d{2})");

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public CnfinFlashAdapter(RestClient.Builder builder, Clock clock) {
        super(builder.requestFactory(requestFactory()).build(), clock);
    }

    /** 全参构造（单测注入受控 RestClient 与时钟）。 */
    CnfinFlashAdapter(RestClient restClient, Clock clock) {
        super(restClient, clock);
    }

    @Override
    String referer() {
        return "http://www.cnfin.com/";
    }

    /** 解析首页新华快讯块锚点（包内可见，fixture 单测直调零外呼）。 */
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
            throw new FeedFetchException("新华财经结构漂移（kx/detail 快讯锚点缺失）: " + source.getSourceCode());
        }
        return dedupeByExternalId(items);
    }

    /** 单条转换：锚点文本剥离 HH:mm 前缀成题（无前缀容错：标题原样、墙钟回落 URL 日期北京零点）+ URL 日期拼合分钟精度墙钟。 */
    private RawFeedItem toItem(Element anchor) {
        Matcher flash = FLASH_HREF.matcher(anchor.attr("href").trim());
        if (!flash.matches()) {
            return null;
        }
        String title = cleanTitle(anchor.text());
        if (title == null) {
            return null;
        }
        LocalDate date =
                LocalDate.of(
                        Integer.parseInt(flash.group(1)),
                        Integer.parseInt(flash.group(2)),
                        Integer.parseInt(flash.group(3)));
        Instant publishedAt = date.atStartOfDay(SHANGHAI).toInstant();
        Matcher prefix = TIME_PREFIX.matcher(title);
        if (prefix.find()) {
            LocalTime time =
                    LocalTime.of(
                            Integer.parseInt(prefix.group(1)), Integer.parseInt(prefix.group(2)));
            publishedAt = date.atTime(time).atZone(SHANGHAI).toInstant();
            title = cleanTitle(title.substring(prefix.end()));
            if (title == null) {
                return null;
            }
        }
        return new RawFeedItem(
                flash.group(4), title, null, anchor.absUrl("href"), "新华财经", publishedAt, null);
    }
}
