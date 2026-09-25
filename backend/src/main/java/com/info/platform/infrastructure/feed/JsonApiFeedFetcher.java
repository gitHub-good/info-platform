package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import com.info.platform.domain.feed.SourceConfig;
import com.info.platform.infrastructure.aggregation.FieldMapper;
import com.info.platform.infrastructure.aggregation.FieldMapping;
import com.info.platform.infrastructure.aggregation.Transform;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 通用 JSON API 引擎（M13 T102，方案 §4.3）：GET endpoint（config.headers 透传 UA/Referer）→ 可选剥离 JS 包装
 * （stripPrefix/stripSuffix，金十 {@code var newest=[...];}）→ Jackson 解析 → {@code listPath} 点分导航到条目数组（空
 * = 根数组）→ 逐条 FieldMapper 白名单映射（{@code itemMapping} 必填，保存时校验）。
 *
 * <p>深翻补抓：pages&gt;1 时按 {@code page=n} 查询参数逐页取数（通用翻页约定，源侧特例走 preset 通道）； newest-first
 * 迭代遇已见条目止；触页上限仍未确认追平 → {@code truncated=true}（ADR-0041）。
 */
@Component
public class JsonApiFeedFetcher implements com.info.platform.domain.feed.FeedFetcher {

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FieldMapper fieldMapper = new FieldMapper(objectMapper);

    /** 生产装配（每源 5s 超时的 SimpleClientHttpRequestFactory）。 */
    @Autowired
    public JsonApiFeedFetcher(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).build());
    }

    /** 全参构造（单测注入受控 RestClient）。 */
    JsonApiFeedFetcher(RestClient restClient) {
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
        CursorType cursorType = source.getConfig().effectiveCursorType();
        int pages = Math.max(1, context.pages());
        for (int page = 1; page <= pages; page++) {
            String body = httpGet(source, page);
            PageResult pageResult = parsePage(body, source, context);
            for (RawFeedItem item : pageResult.items) {
                if (isSeen(item.cursorValue(), context.cursorValue(), cursorType)) {
                    seenKnownItem = true;
                    break;
                }
                items.add(item);
            }
            // 页内遇已见条目或空页 → 追平，无需继续深翻
            if (seenKnownItem || pageResult.items.isEmpty()) {
                seenKnownItem = true;
                break;
            }
        }
        boolean truncated = !seenKnownItem && cursorType != CursorType.NONE && pages > 1;
        return new FetchResult(List.copyOf(items), truncated);
    }

    /** 解析单页 body（包内可见的解析管线入口，fixture 单测经 {@link #parse} 直调零外呼）。 */
    private PageResult parsePage(String body, InfoSource source, FetchContext context) {
        String payload = stripWrapper(body, source.getConfig());
        JsonNode arrayNode = navigate(payload, source);
        List<RawFeedItem> items = new ArrayList<>(arrayNode.size());
        for (JsonNode entry : arrayNode) {
            items.add(mapEntry(entry, source));
        }
        return new PageResult(items);
    }

    /** fixture 解析入口（单页，不含游标止步——止步逻辑在 {@link #fetch} 的页面循环内）。 */
    FetchResult parse(String body, InfoSource source, FetchContext context) {
        PageResult page = parsePage(body, source, context);
        CursorType cursorType = source.getConfig().effectiveCursorType();
        boolean hasCursor = context.cursorValue() != null && !context.cursorValue().isBlank();
        List<RawFeedItem> items = new ArrayList<>();
        boolean seen = !hasCursor;
        for (RawFeedItem item : page.items) {
            if (isSeen(item.cursorValue(), context.cursorValue(), cursorType)) {
                seen = true;
                break;
            }
            items.add(item);
        }
        boolean truncated = !seen && cursorType != CursorType.NONE && context.pages() > 1;
        return new FetchResult(List.copyOf(items), truncated);
    }

    private String httpGet(InfoSource source, int page) {
        String url =
                UriComponentsBuilder.fromUriString(source.getEndpoint())
                        .queryParam("page", page)
                        .build()
                        .toUriString();
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(url);
            source.getConfig().headers().forEach(spec::header);
            String body = spec.retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new FeedFetchException(
                        "JSON 空响应 " + source.getSourceCode() + " " + source.getEndpoint());
            }
            return body;
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    "JSON 取数失败 " + source.getSourceCode() + " " + url + ": " + e.getMessage(), e);
        }
    }

    /** 包装剥离：stripPrefix/stripSuffix 配置化（先剥后解析；未配置则原样）。 */
    private static String stripWrapper(String body, SourceConfig config) {
        String payload = body.trim();
        String prefix = config.stripPrefix();
        if (prefix != null && !prefix.isBlank() && payload.startsWith(prefix)) {
            payload = payload.substring(prefix.length());
        }
        String suffix = config.stripSuffix();
        if (suffix != null && !suffix.isBlank() && payload.endsWith(suffix)) {
            payload = payload.substring(0, payload.length() - suffix.length());
        }
        return payload.trim();
    }

    /** Jackson 解析 + listPath 点分导航到条目数组（空/null = 根数组）。 */
    private JsonNode navigate(String payload, InfoSource source) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new FeedFetchException(
                    "JSON 解析失败 " + source.getSourceCode() + ": " + e.getOriginalMessage(), e);
        }
        JsonNode array = root;
        String listPath = source.getConfig().listPath();
        if (listPath != null && !listPath.isBlank()) {
            for (String segment : listPath.split("\\.")) {
                array = array.path(segment);
            }
        }
        if (!array.isArray()) {
            throw new FeedFetchException(
                    "listPath 未命中条目数组（"
                            + (listPath == null || listPath.isBlank() ? "根" : listPath)
                            + "）: "
                            + source.getSourceCode());
        }
        return array;
    }

    /** 单条白名单映射：FieldMapper 按目标字段产出（缺失字段不产出 → null 透传，title 过滤归摄取层）。 */
    private RawFeedItem mapEntry(JsonNode entry, InfoSource source) {
        Map<String, Object> raw =
                objectMapper.convertValue(entry, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> mapped = fieldMapper.map(raw, fieldMappings(source));
        String externalId = str(mapped.get("externalId"));
        String title = str(mapped.get("title"));
        String summary = str(mapped.get("summary"));
        String url = str(mapped.get("url"));
        String author = str(mapped.get("author"));
        Instant publishedAt = null;
        Object rawPublishedAt = mapped.get("publishedAt");
        if (rawPublishedAt instanceof Instant instant) {
            publishedAt = instant;
        } else if (rawPublishedAt != null) {
            publishedAt = RssFeedFetcher.parseDate(String.valueOf(rawPublishedAt));
        }
        String cursor =
                switch (source.getConfig().effectiveCursorType()) {
                    case ID -> externalId;
                    case TIME -> publishedAt == null ? null : publishedAt.toString();
                    case NONE -> null;
                };
        return new RawFeedItem(externalId, title, summary, url, author, publishedAt, cursor);
    }

    private static List<FieldMapping> fieldMappings(InfoSource source) {
        return source.getConfig().mappings().stream()
                .map(m -> new FieldMapping(m.source(), m.target(), Transform.from(m.transform())))
                .toList();
    }

    /** 已见判定（与 RssFeedFetcher 同口径：ID 数值 / TIME 时间；解析失败视作未见）。 */
    private static boolean isSeen(String cursorValue, String stored, CursorType type) {
        if (stored == null || stored.isBlank() || cursorValue == null) {
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

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    /** 单页解析中间结果。 */
    private record PageResult(List<RawFeedItem> items) {}
}
