package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 新浪财经 7×24 预置适配器（M13 T106，方案 §3.4/§4.6，bean 名 {@code sinaZhiboAdapter} 与目录种子一致）。
 *
 * <p>实测字段口径（2026-09-25 外呼复核，与普查样本差异见 ADR-0042）：条目数组 {@code result.data.feed.list[]}，
 * newest-first；{@code id} 数值游标；{@code rich_text} HTML 经 jsoup 清洗为标题（普查记「richtext」实为 rich_text）；
 * {@code create_time} 墙钟时间 "yyyy-MM-dd HH:mm:ss"（北京时间为源侧声明）；{@code docurl} 详情直链； {@code
 * is_delete}=1 的源侧已删条目跳过。
 *
 * <p>翻页：{@code page=n} 查询参数逐页深翻（backfill），newest-first 遇已见 id 止； 页耗尽仍未确认追平 → truncated（ADR-0041）。
 * 频控礼貌：目录 interval=5min（引擎调度侧），本适配器单轮最多 3 页。
 */
@Component(SinaZhiboAdapter.BEAN_NAME)
public class SinaZhiboAdapter implements PresetFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog SINA_ZHIBO）。 */
    public static final String BEAN_NAME = "sinaZhiboAdapter";

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** create_time 墙钟格式（源侧北京时间）。 */
    private static final DateTimeFormatter CREATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 标题清洗后截断上限（快讯标题+摘要级入库，正文不入库——方案 §4.1 大字段策略）。 */
    private static final int TITLE_MAX_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;

    /** 生产装配（5s 超时 + 浏览器 UA/Referer，礼貌抓取）。 */
    @Autowired
    public SinaZhiboAdapter(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).build());
    }

    /** 全参构造（单测注入受控 RestClient）。 */
    SinaZhiboAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    @Override
    public FetchResult fetch(InfoSource source, FetchContext context) {
        boolean hasCursor = context.cursorValue() != null && !context.cursorValue().isBlank();
        boolean seenKnownItem = !hasCursor;
        List<RawFeedItem> items = new ArrayList<>();
        int pages = Math.max(1, context.pages());
        for (int page = 1; page <= pages; page++) {
            List<RawFeedItem> pageItems = parsePage(httpGet(source, page), source);
            for (RawFeedItem item : pageItems) {
                if (isSeen(item.cursorValue(), context.cursorValue())) {
                    seenKnownItem = true;
                    break;
                }
                items.add(item);
            }
            if (seenKnownItem || pageItems.isEmpty()) {
                seenKnownItem = true;
                break;
            }
        }
        boolean truncated = !seenKnownItem && pages > 1;
        return new FetchResult(List.copyOf(items), truncated);
    }

    private String httpGet(InfoSource source, int page) {
        String url =
                UriComponentsBuilder.fromUriString(source.getEndpoint())
                        .queryParam("page", page)
                        .queryParam("page_size", 20)
                        .build()
                        .toUriString();
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(url);
            spec.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
            spec.header("Referer", "https://finance.sina.com.cn");
            String body = spec.retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new FeedFetchException("新浪 7×24 空响应 " + source.getSourceCode() + " " + url);
            }
            return body;
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    "新浪 7×24 取数失败 " + source.getSourceCode() + " " + url + ": " + e.getMessage(),
                    e);
        }
    }

    /** 解析管线（包内可见，fixture 单测直调零外呼）。 */
    List<RawFeedItem> parsePage(String body, InfoSource source) {
        JsonNode list;
        try {
            list = MAPPER.readTree(body).path("result").path("data").path("feed").path("list");
        } catch (Exception e) {
            throw new FeedFetchException(
                    "新浪 7×24 JSON 解析失败 " + source.getSourceCode() + ": " + e.getMessage(), e);
        }
        if (!list.isArray()) {
            throw new FeedFetchException(
                    "新浪 7×24 结构漂移（result.data.feed.list 缺失）: " + source.getSourceCode());
        }
        List<RawFeedItem> items = new ArrayList<>(list.size());
        for (JsonNode entry : list) {
            RawFeedItem item = toItem(entry, source);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 单条转换：id/游标 + rich_text 清洗 + create_time 墙钟 + docurl；is_delete=1 跳过。 */
    private static RawFeedItem toItem(JsonNode entry, InfoSource source) {
        if (entry.path("is_delete").asInt(0) == 1) {
            return null;
        }
        String externalId = entry.path("id").asText();
        if (externalId == null || externalId.isBlank() || "0".equals(externalId)) {
            return null;
        }
        String richText = entry.path("rich_text").asText("");
        String title = cleanText(richText);
        if (title == null) {
            return null;
        }
        Instant publishedAt = parseCreateTime(entry.path("create_time").asText(null));
        String url = textOrNull(entry.path("docurl").asText(null));
        return new RawFeedItem(
                externalId,
                title,
                null,
                url,
                "新浪财经",
                publishedAt,
                // ID 数值游标（目录 config cursorType=ID；其他声明按 NONE 不产出游标）
                source.getConfig().effectiveCursorType() == CursorType.ID ? externalId : null);
    }

    /** richtext HTML → 纯文本（jsoup .text()），清洗后为空返回 null；超长截断保标题级。 */
    private static String cleanText(String richText) {
        String text = Jsoup.parse(richText == null ? "" : richText).text().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= TITLE_MAX_CHARS ? text : text.substring(0, TITLE_MAX_CHARS);
    }

    /** create_time 墙钟（Asia/Shanghai）→ Instant；缺失/不可解析返回 null（发布时间回落抓取时刻归摄取层）。 */
    private static Instant parseCreateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, CREATE_TIME)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 已见判定：ID 数值游标 {@code <=} 已存值（解析失败视作未见，保守多拉由去重兜底）。 */
    private static boolean isSeen(String cursorValue, String stored) {
        if (stored == null || stored.isBlank() || cursorValue == null) {
            return false;
        }
        try {
            return Long.parseLong(cursorValue.trim()) <= Long.parseLong(stored.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
