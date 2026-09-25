package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.infrastructure.aggregation.FieldMapper;
import com.info.platform.infrastructure.aggregation.FieldMapping;
import com.info.platform.infrastructure.aggregation.Transform;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通用 RSS 2.0/Atom 引擎（M13 T101，方案 §4.3：jsoup XML parser，零新依赖——GovPolicyClient 先例）。
 *
 * <p>RSS 2.0 取 {@code channel/item}（title/link/guid 或 dc:identifier/pubDate 或
 * dc:date/description/author）； Atom 取 {@code feed/entry}（title/link[@href 优先
 * rel=alternate]/id/updated 或 published/summary）。默认映射固定； {@code config.itemMapping} 提供则按目标字段覆盖（复用
 * FieldMapper 白名单语义，源字段 = RSS 本地标签名）。
 *
 * <p>脏数据防御：单条字段缺失/日期不可解析降级为 null 不致命；整体非 RSS/Atom 结构抛 {@link FeedFetchException}（含源定位）。
 * 游标止步：newest-first 迭代遇已见条目止；RSS 单文档无法深翻——pages&gt;1 且始终未见已见条目时 {@code truncated=true}
 * （条目可能已滚出源侧窗口，ADR-0041 截断语义）。
 */
@Component
public class RssFeedFetcher implements com.info.platform.domain.feed.FeedFetcher {

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final FieldMapper fieldMapper = new FieldMapper(new ObjectMapper());

    /** 生产装配（每源 5s 超时的 SimpleClientHttpRequestFactory）。 */
    @Autowired
    public RssFeedFetcher(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).build());
    }

    /** 全参构造（单测注入受控 RestClient，如 MockRestServiceServer 绑定产物）。 */
    RssFeedFetcher(RestClient restClient) {
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
        String body;
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(source.getEndpoint());
            source.getConfig().headers().forEach(spec::header);
            body = spec.retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    "RSS 取数失败 "
                            + source.getSourceCode()
                            + " "
                            + source.getEndpoint()
                            + ": "
                            + e.getMessage(),
                    e);
        }
        if (body == null || body.isBlank()) {
            throw new FeedFetchException(
                    "RSS 空响应 " + source.getSourceCode() + " " + source.getEndpoint());
        }
        return parse(body, source, context);
    }

    /** 解析管线（包内可见，fixture 单测直调零外呼）。 */
    FetchResult parse(String xml, InfoSource source, FetchContext context) {
        Document doc = Jsoup.parse(xml, "", Parser.xmlParser());
        List<Element> entries = new ArrayList<>(doc.select("channel > item"));
        boolean atom = false;
        if (entries.isEmpty()) {
            entries.addAll(doc.select("feed > entry"));
            atom = !entries.isEmpty();
        }
        if (entries.isEmpty()) {
            throw new FeedFetchException(
                    "非 RSS/Atom 结构: " + source.getSourceCode() + " " + source.getEndpoint());
        }
        boolean hasCursor = context.cursorValue() != null && !context.cursorValue().isBlank();
        boolean seenKnownItem = !hasCursor;
        List<RawFeedItem> items = new ArrayList<>(entries.size());
        for (Element entry : entries) {
            RawFeedItem item = atom ? atomItem(entry, source) : rssItem(entry, source);
            if (hasCursor
                    && isSeen(
                            item.cursorValue(),
                            context.cursorValue(),
                            source.getConfig().effectiveCursorType())) {
                seenKnownItem = true;
                break;
            }
            items.add(item);
        }
        boolean truncated =
                context.pages() > 1
                        && !seenKnownItem
                        && source.getConfig().effectiveCursorType() != CursorType.NONE;
        return new FetchResult(List.copyOf(items), truncated);
    }

    /** RSS 2.0 item 默认映射（guid 优先，dc:identifier 次之，link 兜底）。 */
    private RawFeedItem rssItem(Element item, InfoSource source) {
        String guid = firstText(item, "guid", "identifier");
        String link = childText(item, "link");
        Map<String, Object> raw = rawFields(item, "pubDate");
        raw.put("link", link);
        raw.put("guid", guid);
        return buildItem(source, raw, guid == null ? link : guid, link);
    }

    /** Atom entry 默认映射（link[@href] 优先 rel=alternate；updated 兜底 published）。 */
    private RawFeedItem atomItem(Element entry, InfoSource source) {
        String id = childText(entry, "id");
        String link = atomLink(entry);
        Map<String, Object> raw = rawFields(entry, "updated", "published");
        raw.put("link", link);
        raw.put("guid", id);
        return buildItem(source, raw, id == null ? link : id, link);
    }

    /** 抽公共子字段为原始映射（itemMapping 覆盖路径与默认路径共用取值）。 */
    private static Map<String, Object> rawFields(Element item, String... dateNames) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("title", childText(item, "title"));
        raw.put("description", childText(item, "description"));
        raw.put("summary", childText(item, "summary"));
        raw.put("author", childText(item, "author"));
        String date = null;
        for (String name : dateNames) {
            date = childText(item, name);
            if (date != null) {
                raw.put("date", date);
                break;
            }
        }
        return raw;
    }

    /** 组装条目：itemMapping 空走默认映射；否则 FieldMapper 白名单覆盖目标字段。 */
    private RawFeedItem buildItem(
            InfoSource source, Map<String, Object> raw, String externalId, String link) {
        String title = str(raw.get("title"));
        String summary = str(raw.get("description"));
        if (summary == null) {
            summary = str(raw.get("summary"));
        }
        String author = str(raw.get("author"));
        Instant publishedAt = parseDate(str(raw.get("date")));
        if (!source.getConfig().mappings().isEmpty()) {
            Map<String, Object> mapped = fieldMapper.map(raw, fieldMappings(source));
            if (mapped.containsKey("title")) {
                title = str(mapped.get("title"));
            }
            if (mapped.containsKey("summary")) {
                summary = str(mapped.get("summary"));
            }
            if (mapped.containsKey("author")) {
                author = str(mapped.get("author"));
            }
            if (mapped.containsKey("publishedAt")) {
                publishedAt = parseDate(str(mapped.get("publishedAt")));
            }
            if (mapped.containsKey("externalId")) {
                externalId = str(mapped.get("externalId"));
            }
            if (mapped.containsKey("url")) {
                link = str(mapped.get("url"));
            }
        }
        String cursor = cursorValueOf(source, externalId, publishedAt);
        return new RawFeedItem(externalId, title, summary, link, author, publishedAt, cursor);
    }

    private static List<FieldMapping> fieldMappings(InfoSource source) {
        return source.getConfig().mappings().stream()
                .map(m -> new FieldMapping(m.source(), m.target(), Transform.from(m.transform())))
                .toList();
    }

    private static String cursorValueOf(InfoSource source, String externalId, Instant publishedAt) {
        return switch (source.getConfig().effectiveCursorType()) {
            case ID -> externalId;
            case TIME -> publishedAt == null ? null : publishedAt.toString();
            case NONE -> null;
        };
    }

    /** 已见判定：游标值 ≤ 已存游标（ID 数值比较 / TIME 时间比较；解析失败视作未见，靠去重兜底）。 */
    private static boolean isSeen(String cursorValue, String stored, CursorType type) {
        if (cursorValue == null) {
            return false;
        }
        try {
            if (type == CursorType.ID) {
                return Long.parseLong(cursorValue.trim()) <= Long.parseLong(stored.trim());
            }
            if (type == CursorType.TIME) {
                return Instant.parse(cursorValue).compareTo(Instant.parse(stored)) <= 0;
            }
        } catch (RuntimeException ignore) {
            return false;
        }
        return false;
    }

    /** 宽容日期解析：ISO-8601（Instant/OffsetDateTime）/ RFC-1123（RSS pubDate）/ 裸本地时间（按 UTC）；失败返回 null。 */
    static Instant parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignore) {
            // try next
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignore) {
            // try next
        }
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text));
        } catch (DateTimeParseException ignore) {
            // try next
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignore) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 子元素文本（按本地标签名匹配，命名空间前缀剥除——dc:identifier/dc:date 兼容）。 */
    private static String childText(Element item, String localName) {
        for (Element child : item.children()) {
            if (localName(child).equalsIgnoreCase(localName)) {
                return child.text();
            }
        }
        return null;
    }

    private static String firstText(Element item, String... localNames) {
        for (String name : localNames) {
            String text = childText(item, name);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String localName(Element element) {
        String tag = element.tagName();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    /** Atom link：优先 rel=alternate/无 rel，兜底首个带 href 的 link。 */
    private static String atomLink(Element entry) {
        String fallback = null;
        for (Element child : entry.children()) {
            if (!"link".equalsIgnoreCase(localName(child)) || !child.hasAttr("href")) {
                continue;
            }
            String rel = child.attr("rel");
            if (rel.isEmpty() || "alternate".equals(rel)) {
                return child.attr("href");
            }
            if (fallback == null) {
                fallback = child.attr("href");
            }
        }
        return fallback;
    }
}
