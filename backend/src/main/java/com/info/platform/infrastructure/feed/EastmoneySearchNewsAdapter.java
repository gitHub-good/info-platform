package com.info.platform.infrastructure.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.FetchResult;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 东财个股资讯搜索预置适配器（M29 T253，方案 §4 C6 / ADR-0064 随批 5，bean 名 {@code eastmoneySearchNewsAdapter} 与目录种子
 * 一致）：{@code em-search-hk} / {@code em-search-us} 两源共用——按 {@code source_code} 后缀（{@code -hk}/{@code
 * -us}）区分市场， 标的池 round-robin 轮询（keyword=名称，Spike-E E-4a 实测契约）。
 *
 * <p><b>实测口径（2026-09-29 探针复核）</b>：GET {@code
 * search-api-web.eastmoney.com/search/jsonp?cb=cb&param=<URL-encoded JSON>}；param = {@code
 * {"uid":"","keyword":名称,"type":["cmsArticleWebOld"],"client":"web","clientType":"web",
 * "clientVersion":"curr","param":{"cmsArticleWebOld":{"searchScope":"default","sort":"default","pageIndex":1,
 * "pageSize":5,"preTag":"<em>","postTag":"</em>"}}}}；响应 JSONP {@code cb({...})}，条目数组 {@code
 * result.cmsArticleWebOld[]}，字段 {@code date}（{@code yyyy-MM-dd HH:mm:ss} 北京墙钟）/ {@code title} /
 * {@code content}（含 {@code <em>} 高亮标签，需剥除）/ {@code code}（文章 id，externalId）/ {@code url} 直链 / {@code
 * mediaName}。
 *
 * <p><b>round-robin 游标</b>：轮转位为本适配器<b>进程内原子计数</b>（每市场独立）——既有 {@code source_poll_state.cursor_value}
 * 的「只进不退 + 按游标类型单调比较」语义无法承载环形指针（回绕后永不推进），复用即破坏增量语义，故不落库；重启回卷头部的重复 抓取由 {@code (source_id,
 * external_id)} 唯一索引幂等吸收（方案 C6「游标存 cursor_value」的实现偏差回注）。
 *
 * <p><b>限频</b>（Spike-E §7 条款 5：1 req/s 起步观察）：请求间 {@code feed.em-search.request-interval-millis}（缺省
 * 1000ms） 礼貌间隔；每 tick 标的数 {@code feed.em-search.subjects-per-tick}（缺省 40，方案 C6）。单标的搜索失败 WARN
 * 跳过继续（部分轮 有效）；全部失败抛 {@link FeedFetchException} 计整轮失败（退避由摄取层承接）。
 *
 * <p>robots：search-api-web 宿主无 robots（Spike-E §8 三验 #2 达标）。池快照每 tick 现查（本地 SQLite 毫秒级，免缓存复杂度）， 取
 * status=1 + 名长 ≥2，按 subject_code 升序保证轮转确定性。
 */
@Component(EastmoneySearchNewsAdapter.BEAN_NAME)
public class EastmoneySearchNewsAdapter implements PresetFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog EM_SEARCH_HK/US）。 */
    public static final String BEAN_NAME = "eastmoneySearchNewsAdapter";

    private static final Logger log = LoggerFactory.getLogger(EastmoneySearchNewsAdapter.class);

    /** 搜索端点（Spike-E E-4a 实测宿主，非 push2 族未封禁）。 */
    static final String SEARCH_URL = "https://search-api-web.eastmoney.com/search/jsonp";

    /** JSONP 回调名（内部接口必带 cb 参数）。 */
    private static final String CALLBACK = "cb";

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 源侧发布时间墙钟（北京时间为源侧声明）。 */
    private static final DateTimeFormatter SOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 标题截断上限（快讯标题+摘要级入库，SinaZhiboAdapter 同款）。 */
    static final int TITLE_MAX_CHARS = 300;

    /** 摘要截断上限（大字段策略，方案 §4.1）。 */
    static final int SUMMARY_MAX_CHARS = 500;

    /** 池名最小长度（单字名误并风险高，SubjectMatcher 同款）。 */
    private static final int MIN_NAME_LENGTH = 2;

    private final RestClient restClient;

    private final SubjectRepository subjectRepository;

    private final int subjectsPerTick;

    private final int pageSize;

    private final long requestIntervalMillis;

    /** 每市场轮转位（进程内；回绕重复由 (source_id, external_id) 唯一索引幂等吸收）。 */
    private final Map<Market, AtomicInteger> rotations = new ConcurrentHashMap<>();

    /** 生产装配（5s 超时 + 浏览器 UA；限频三旋钮走 {@code feed.em-search.*} yml）。 */
    @Autowired
    public EastmoneySearchNewsAdapter(
            RestClient.Builder builder,
            SubjectRepository subjectRepository,
            @Value("${feed.em-search.subjects-per-tick:40}") int subjectsPerTick,
            @Value("${feed.em-search.page-size:5}") int pageSize,
            @Value("${feed.em-search.request-interval-millis:1000}") long requestIntervalMillis) {
        this(
                builder.requestFactory(requestFactory()).build(),
                subjectRepository,
                subjectsPerTick,
                pageSize,
                requestIntervalMillis);
    }

    /** 全参构造（单测注入受控 RestClient 与限频参数）。 */
    EastmoneySearchNewsAdapter(
            RestClient restClient,
            SubjectRepository subjectRepository,
            int subjectsPerTick,
            int pageSize,
            long requestIntervalMillis) {
        this.restClient = restClient;
        this.subjectRepository = subjectRepository;
        this.subjectsPerTick = Math.max(1, subjectsPerTick);
        this.pageSize = Math.max(1, pageSize);
        this.requestIntervalMillis = Math.max(0, requestIntervalMillis);
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }

    @Override
    public FetchResult fetch(InfoSource source, FetchContext context) {
        Market market = marketOf(source);
        List<Subject> pool = loadPool(market);
        if (pool.isEmpty()) {
            log.warn("em-search 标的池为空，本轮空轮转 source={}", source.getSourceCode());
            return FetchResult.of(List.of());
        }
        List<Subject> selected = selectRound(pool, market);
        List<RawFeedItem> items = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < selected.size(); i++) {
            try {
                items.addAll(search(Subject::getName, selected.get(i)));
            } catch (RuntimeException e) {
                failed++;
                log.warn(
                        "em-search 单标的搜索失败（跳过继续）source={} subject={}: {}",
                        source.getSourceCode(),
                        selected.get(i).getSubjectCode().value(),
                        e.toString());
            }
            paceBetween(i, selected.size());
        }
        if (failed > 0 && failed == selected.size() && !selected.isEmpty()) {
            throw new FeedFetchException(
                    "em-search 全部标的搜索失败 source=" + source.getSourceCode() + " failed=" + failed);
        }
        // newest-first 惯例：按发布时间降序（同刻稳定序 externalId 降序）
        items.sort(
                (a, b) -> {
                    int byTime =
                            b.publishedAt() == null || a.publishedAt() == null
                                    ? 0
                                    : b.publishedAt().compareTo(a.publishedAt());
                    return byTime != 0
                            ? byTime
                            : String.valueOf(b.externalId())
                                    .compareTo(String.valueOf(a.externalId()));
                });
        return FetchResult.of(items);
    }

    /** 市场解析：source_code 后缀 -hk/-us（目录两源共用本 adapter 的区分键）。 */
    static Market marketOf(InfoSource source) {
        String code = source.getSourceCode();
        if (code.endsWith("-hk")) {
            return Market.HK;
        }
        if (code.endsWith("-us")) {
            return Market.US;
        }
        throw new FeedFetchException("em-search 源 code 后缀非法（须 -hk/-us）: " + code);
    }

    /** 池装载：status=1 + 名长 ≥2 + 按 subject_code 升序（轮转确定性）。 */
    private List<Subject> loadPool(Market market) {
        return subjectRepository.loadBucket(market, SubjectType.STOCK).stream()
                .filter(
                        subject ->
                                subject.getStatus() == SubjectStatus.ENABLED
                                        && subject.getName() != null
                                        && subject.getName().length() >= MIN_NAME_LENGTH)
                .sorted(Comparator.comparing(subject -> subject.getSubjectCode().value()))
                .toList();
    }

    /** 本 tick 轮转切片：从原子轮转位起取 N 只（回绕），并推进轮转位。 */
    private List<Subject> selectRound(List<Subject> pool, Market market) {
        AtomicInteger rotation = rotations.computeIfAbsent(market, key -> new AtomicInteger());
        int size = pool.size();
        int start = Math.floorMod(rotation.getAndAdd(subjectsPerTick), size);
        int take = Math.min(subjectsPerTick, size);
        List<Subject> selected = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            selected.add(pool.get((start + i) % size));
        }
        return selected;
    }

    /** 单标的搜索：JSONP 剥壳 → result.cmsArticleWebOld[] 逐条映射（剥 {@code <em>}）。 */
    private List<RawFeedItem> search(
            java.util.function.Function<Subject, String> keyword, Subject subject) {
        String body = httpGet(keyword.apply(subject));
        JsonNode articles = parseArticles(body, subject);
        List<RawFeedItem> items = new ArrayList<>();
        if (articles == null) {
            return items;
        }
        for (JsonNode article : articles) {
            RawFeedItem item = mapArticle(article);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 响应解析：剥 {@code cb(..)[;]} → {@code result.cmsArticleWebOld[]}（缺失/非数组返回 null，白名单语义）。 */
    private JsonNode parseArticles(String body, Subject subject) {
        String payload = stripJsonp(body);
        try {
            JsonNode root = MAPPER.readTree(payload);
            return root.path("result").path("cmsArticleWebOld");
        } catch (Exception e) {
            log.warn(
                    "em-search 响应不可解析 subject={}: {}",
                    subject.getSubjectCode().value(),
                    e.toString());
            return null;
        }
    }

    /** JSONP 剥壳：{@code cb({...});} → {@code {...}}（防御尾分号与空白；格式异常原样上抛由调用方记失败）。 */
    static String stripJsonp(String body) {
        String text = body == null ? "" : body.trim();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close <= open) {
            throw new FeedFetchException("em-search JSONP 壳缺失: " + abbreviate(text));
        }
        return text.substring(open + 1, close);
    }

    /** 条目映射：code→externalId、date→publishedAt（北京墙钟）、剥 {@code <em>}、截断。title 与 externalId 皆缺失行丢弃。 */
    private RawFeedItem mapArticle(JsonNode article) {
        String title = stripEm(textOf(article.get("title")));
        String url = textOf(article.get("url"));
        String externalId = textOf(article.get("code"));
        if (title.isBlank() && externalId.isBlank()) {
            return null;
        }
        String media = textOf(article.get("mediaName"));
        return new RawFeedItem(
                externalId.isBlank() ? null : externalId,
                truncate(title, TITLE_MAX_CHARS),
                truncate(stripEm(textOf(article.get("content"))), SUMMARY_MAX_CHARS),
                url.isBlank() ? null : url,
                media.isBlank() ? null : media,
                parseTime(textOf(article.get("date"))),
                null); // cursorType=NONE
    }

    /** 请求间隔礼貌 sleep（末标的不停顿；被中断视为轮次终止信号上抛）。 */
    private void paceBetween(int index, int total) {
        if (requestIntervalMillis <= 0 || index + 1 >= total) {
            return;
        }
        try {
            Thread.sleep(requestIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedFetchException("em-search 请求间隔被中断", e);
        }
    }

    private String httpGet(String keyword) {
        // URI 直传（绕过 uri(String) 的模板二次编码——param 已整体 URL 编码，% 会被再转 %25）
        java.net.URI uri =
                java.net.URI.create(
                        SEARCH_URL + "?cb=" + CALLBACK + "&param=" + encode(buildParam(keyword)));
        return restClient
                .get()
                .uri(uri)
                .accept(MediaType.ALL)
                .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                                + " Chrome/120 Safari/537.36")
                .header("Referer", "https://so.eastmoney.com/")
                .retrieve()
                .body(String.class);
    }

    /** 请求 param JSON（Spike-E E-4a 实测契约；preTag/postTag 触发源侧 <em> 高亮）。 */
    String buildParam(String keyword) {
        return """
                {"uid":"","keyword":"%s","type":["cmsArticleWebOld"],"client":"web","clientType":"web",\
                "clientVersion":"curr","param":{"cmsArticleWebOld":{"searchScope":"default","sort":"default",\
                "pageIndex":1,"pageSize":%d,"preTag":"<em>","postTag":"</em>"}}}"""
                .formatted(escapeJson(keyword), pageSize);
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /** JSON 字符串转义（keyword 含引号/反斜杠防御）。 */
    private static String escapeJson(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static java.time.Instant parseTime(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, SOURCE_TIME).atZone(BEIJING).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("em-search 发布时间不可解析，置空 date='{}'", raw);
            return null;
        }
    }

    private static String stripEm(String raw) {
        return raw == null ? "" : raw.replaceAll("</?em>", "").trim();
    }

    private static String textOf(JsonNode node) {
        return node == null || !node.isTextual() ? "" : node.asText();
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }
}
