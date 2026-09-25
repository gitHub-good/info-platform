package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 新浪新闻源真实 adapter（T06）。
 *
 * <p>真实接入新浪 {@code feed.mix.sina.com.cn} 滚动新闻：取财经分类（lid=2510）全市场新闻流，在 {@link #doFetch}
 * 内按标的名称/代码关键词匹配过滤出相关条目，逐条字段映射后以 {@code data.items} 列表承载落 {@code SourceResult.data}。 与 {@link
 * MockNewsSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 *
 * <p><b>个股关联策略</b>（核心难点，Spike-1 §6.5）：新浪滚动新闻为<b>全市场流</b>，不支持个股过滤 （2026-09-21 curl 实测 {@code
 * stock}/{@code k} 参数被忽略）。故 {@link #doFetch} 拉全市场 {@code num} 条后本地按关键词 {@code contains}
 * 过滤：标题({@code title}) 或 关键词({@code keywords}) 含 {@code subject.name}（如「贵州茅台」）<b>OR</b> 含 6
 * 位代码（如「600519」）算相关。未命中 → {@link Optional#empty()} → MISSING（当日无相关新闻，不阻断）。命中条目逐条映射。
 *
 * <p><b>可替换性</b>：取数（{@link SinaNewsClient#fetchRollNews}，纯全市场流）与关联过滤（{@link #isRelevant}）
 * 职责分离——后续切东方财富个股资讯 API（Spike-1 §6.5 二级候选 🟡 待验）时，仅需换 client + {@code doFetch} 取数路径；{@link
 * #isRelevant} 关键词匹配可保留（东财已按个股过滤，{@code contains} 仍命中）或弃用 （直接映射全部）。匹配策略为 package-private
 * 静态方法，便于独立单测与未来替换。
 *
 * <p>代码派生：6 位代码用于关键词匹配，优先 {@code eastmoney_code} 键；缺省从 {@code eastmoney} secid 按 {@code .}
 * 切分派生（Spike-1 §5，逻辑同 {@link AnnounceSourceAdapter}）。两者皆缺 → 仅按 {@code subject.name}
 * 匹配（仍可命中标题含名称的新闻）。V2 种子（贵州茅台）存 {@code eastmoney="1.600519"}，派生 {@code 600519}。
 *
 * <p><b>列表型分区映射策略</b>（同 {@link AnnounceSourceAdapter}）：{@link FieldMapper#map} 仅做单条 flat Map
 * 映射，无法遍历列表。故 {@code doFetch} 内：① 对每条命中新闻预处理 {@code ctime}（见下）； ② 用 {@link #itemMapping}（{@code
 * field-mapping/sina-news.json}）逐条映射； ③ 收集为 {@code items} 列表包装进 {@link RawFetch#data}（{@code
 * Map.of("items", ...)}）。 {@link #mappingConfig} 返回 {@code items→items} passthrough：模板层 {@code
 * fieldMapper.map(data, mappingConfig)} 作用在整张 data map（单层），对已规范化的 {@code items} 列表原样透传进 {@code
 * SourceResult.data["items"]}。应用层聚合服务从 {@code data.items} 键提取 （同 {@link MockNewsSourceAdapter} 契约）。
 *
 * <p><b>ctime 字段偏差处理</b>：新浪 {@code ctime} 实测为 <b>Unix 秒级时间戳字符串</b>（如 {@code "1748275048"}）， 非
 * {@code yyyy-MM-dd HH:mm:ss}（Spike-1 §4.5 未注明，与公告源 {@code notice_date} 日期串不同）。 {@link FieldMapper}
 * 的 {@code to_iso_date} 仅解析日期/日期时间格式串，无法处理 epoch。故 {@code doFetch} 内对每条新闻预处理：epoch 秒 → {@code
 * Asia/Shanghai} {@link LocalDateTime} → {@code yyyy-MM-dd HH:mm:ss} 字符串， 回填 flat {@code ctime}
 * 键，再经 {@code to_iso_date} 映射为 ISO（与其他源 {@code publishedAt} 格式统一）。 非数字/解析失败则移除该字段（白名单语义，不产出 {@code
 * publishedAt}）。转换属字段预处理实现细节，<b>不改 FieldMapper 框架契约</b>（对齐角色红线：不动接口/数据模型）。
 *
 * <p>弹性：超时 2s、重试 0（{@link ResilienceSpec#noRetry(Duration) of 2s}，对齐技术方案 §4.3 流程 1 新闻 「超时 2s 重试
 * 0」，同 ADR-0011 公告源预算收敛取舍精神）。降级默认 MISSING——新闻源挂或当日无相关新闻 均不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 §4.5 + 2026-09-21 curl 实测：docid/title/ctime/intro/url/media_name/keywords 为
 * 列表项顶层字段（实测确认，与 §4.5 一致）；{@code ctime} 为 epoch 秒（偏差见上）。
 */
public class NewsSourceAdapter extends AbstractSourceAdapter {

    /** subject.external_codes 中东财 6 位代码的键名（优先取）。 */
    private static final String EASTMONEY_CODE_KEY = "eastmoney_code";

    /** subject.external_codes 中东财 secid 的键名（派生 6 位代码的回退来源，V2 种子用此键）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** 新浪为中国新闻源，ctime 为 UTC epoch 秒，按 +08:00 落地展示时间。 */
    private static final ZoneId NEWS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter NEWS_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...], "size":N, "hasMore":B}}）的 items 列表与分页元数据 原样透传。
     * 逐条字段映射在 {@link #doFetch} 内用 {@link #itemMapping} 完成。M12 T92 增 size（源页大小回显）/hasMore（源页耗尽信号）
     * 两键——FieldMapper 白名单语义：缺失/null 键不产出。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(
                    new FieldMapping("items", "items", Transform.NONE),
                    new FieldMapping("size", "size", Transform.NONE),
                    new FieldMapping("hasMore", "hasMore", Transform.NONE));

    private final SinaNewsClient client;
    private final FieldMapper fieldMapper;
    private final List<FieldMapping> itemMapping;

    public NewsSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            SinaNewsClient client) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        // 模板层 fieldMapper 为 private，子类需另持引用以在 doFetch 内逐条映射（同 AnnounceSourceAdapter）。
        this.fieldMapper = fieldMapper;
        this.itemMapping = fieldMapper.loadMapping("field-mapping/sina-news.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.NEWS;
    }

    @Override
    protected String sourceLabel() {
        return "新浪财经新闻";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        Optional<List<Map<String, Object>>> rawList = client.fetchRollNews();
        if (rawList.isEmpty()) {
            // 全市场流为空（新浪异常返回/网络空体）→ MISSING（成功调用，非异常）
            return Optional.empty();
        }
        String subjectName = subject.getName();
        String stockCode = resolveStockCode(subject);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> raw : rawList.get()) {
            if (isRelevant(raw, subjectName, stockCode)) {
                Map<String, Object> flat = normalizeCtime(raw);
                Map<String, Object> mapped = fieldMapper.map(flat, itemMapping);
                hits.add(Collections.unmodifiableMap(new LinkedHashMap<>(mapped)));
            }
        }
        if (hits.isEmpty()) {
            // 全市场流中无与该标的相关的新闻 → MISSING（不阻断）
            return Optional.empty();
        }
        Map<String, Object> data = Map.of("items", List.copyOf(hits));
        return Optional.of(new RawFetch(data, sourceLabel(), Instant.now()));
    }

    /**
     * 分区子端点分页取数（M12 T92，方案 §4.1.3/ADR-0037 决策 2/D4）：单请求 = 单源页过滤命中 + {@code hasMore}
     * 源页耗尽信号——后端契约<b>无状态</b>，「新增」判定归前端（累积 externalId 集合）。
     *
     * <p>语义细则：{@code items} = 该源页经 {@link #isRelevant} 过滤后的命中条目（可为空——无命中但源页有条目仍
     * OK，hasMore 按源页满否如实）；空源页/流耗尽 → OK + 空列表 + {@code hasMore:false}（耗尽信号是有效数据，非
     * MISSING）；{@code hasMore} = 本源页条数 == 源页大小且非空。{@code size} 参数忽略——源页大小是运维配置
     * {@code newsPageSize}，不属调用方自由度（data 附 {@code size} 回显生效值）。
     */
    @Override
    public SourceResult fetchPage(Subject subject, int page, int size) {
        return runGuarded(subject, () -> doFetchPage(subject, page));
    }

    private Optional<RawFetch> doFetchPage(Subject subject, int page) throws Exception {
        int pageSize = client.newsPageSize();
        Optional<List<Map<String, Object>>> rawList = client.fetchRollNews(page);
        if (rawList.isEmpty()) {
            // 空源页/流耗尽：OK + items:[] + hasMore:false（停止信号；非 MISSING——契约 §4.1.3）
            return Optional.of(pageData(List.of(), pageSize, false));
        }
        String subjectName = subject.getName();
        String stockCode = resolveStockCode(subject);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> raw : rawList.get()) {
            if (isRelevant(raw, subjectName, stockCode)) {
                Map<String, Object> flat = normalizeCtime(raw);
                Map<String, Object> mapped = fieldMapper.map(flat, itemMapping);
                hits.add(Collections.unmodifiableMap(new LinkedHashMap<>(mapped)));
            }
        }
        boolean hasMore = rawList.get().size() == pageSize;
        return Optional.of(pageData(List.copyOf(hits), pageSize, hasMore));
    }

    private RawFetch pageData(List<Map<String, Object>> items, int pageSize, boolean hasMore) {
        return new RawFetch(
                Map.of("items", items, "size", pageSize, "hasMore", hasMore),
                sourceLabel(),
                Instant.now());
    }

    /**
     * 个股关联关键词匹配（可替换策略）：标题或关键词含标的名 <b>OR</b> 6 位代码 → 相关。
     *
     * <p>简单 {@code contains} 字面匹配（Spike-1 §6.5）；null 安全（缺字段按空串处理）。6 位代码精确度高， 误命中风险低。后续切东财个股资讯 API
     * 时可复用（已按个股过滤，contains 仍命中）或弃用。
     */
    static boolean isRelevant(Map<String, Object> rawNews, String subjectName, String stockCode) {
        String title = stringOf(rawNews.get("title"));
        String keywords = stringOf(rawNews.get("keywords"));
        boolean nameHit =
                subjectName != null
                        && !subjectName.isBlank()
                        && (title.contains(subjectName) || keywords.contains(subjectName));
        boolean codeHit =
                stockCode != null
                        && !stockCode.isBlank()
                        && (title.contains(stockCode) || keywords.contains(stockCode));
        return nameHit || codeHit;
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 预处理 ctime：新浪 ctime 为 Unix 秒级时间戳字符串，转 {@code Asia/Shanghai} {@code yyyy-MM-dd HH:mm:ss}， 回填
     * flat map 供 {@code to_iso_date} 映射。非数字/解析失败则移除（不产出 publishedAt，白名单语义）。
     */
    private static Map<String, Object> normalizeCtime(Map<String, Object> raw) {
        Map<String, Object> flat = new LinkedHashMap<>(raw);
        Object ctime = flat.get("ctime");
        if (ctime != null) {
            try {
                long epoch = Long.parseLong(ctime.toString().trim());
                String datetime =
                        NEWS_DATETIME.format(
                                LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), NEWS_ZONE));
                flat.put("ctime", datetime);
            } catch (NumberFormatException e) {
                flat.remove("ctime");
            }
        }
        return flat;
    }

    /**
     * 派生 6 位证券代码用于关键词匹配：优先 {@code eastmoney_code} 键；缺省从 {@code eastmoney} secid（如 {@code
     * 1.600519}）按首个 {@code .} 切分派生为 {@code 600519}。两者皆缺/空 → null（仅按名称匹配）。
     */
    private static String resolveStockCode(Subject subject) {
        Map<String, String> externalCodes = subject.getExternalCodes();
        if (externalCodes == null) {
            return null;
        }
        String code = externalCodes.get(EASTMONEY_CODE_KEY);
        if (code != null && !code.isBlank()) {
            return code;
        }
        String secid = externalCodes.get(EASTMONEY_SECID_KEY);
        if (secid == null || secid.isBlank()) {
            return null;
        }
        int dot = secid.indexOf('.');
        return dot >= 0 ? secid.substring(dot + 1) : secid;
    }
}
