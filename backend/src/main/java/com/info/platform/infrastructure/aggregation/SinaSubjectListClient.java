package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 新浪沪深 A 股列表 HTTP 客户端（M7 备选源，ADR-0030）——{@link SubjectListSource} 端口的 <b>仅 A 股桶</b>实现， 由 {@link
 * RoutingSubjectListSource} 在 auto 降级 / 强制 sina 时调用。
 *
 * <p>端点 {@code Market_Center.getHQNodeData}（2026-09-22 实测契约）：裸 JSON 数组、非 ASCII 由源按 JSON unicode
 * 转义输出（content-type 声明 gbk 但体为 ASCII，编码无歧义）、<b>无总数字段</b>——终止条件改为「空页（{@code []} 或字面量 {@code null}）/
 * 不足 {@code num} 的短页」，并以「首页为空即放弃」+「全量结果为空即放弃」双重防假空（源故障返空不能当全量， 否则整桶标的被误判缺失）。
 *
 * <p>字段映射（ADR-0030，对齐既有 SubjectSnapshot 形态）：{@code symbol} 前缀 {@code sh}→SH（secid 沪 {@code 1.代码}）、
 * {@code sz}→SZ（secid 深 {@code 0.代码}）；<b>{@code bj}（北交所）跳过</b>——不在需求口径（ADR-0027 fs 不含北交所）， 且 secid
 * 派生规则未实测、不引入虚构主数据；{@code name} trim 空名行丢弃；<b>无行业字段 → industry 恒 null</b>（PM 裁 「以源为准」）。
 *
 * <p>软限频（与 {@link SinaNewsClient} 同款）：每请求带浏览器 UA + {@code Referer: https://finance.sina.com.cn}（裸请求
 * 易 403）；页间隔复用 {@code subject.sync.page-interval-millis}（与东财共用一个礼貌限速旋钮），{@code
 * subject.sync.max-pages} 防御翻页失控。单页弹性由 {@link ResilienceRunner} 包裹（超时 10s——§3.1 实测新浪冷响应 6.8s，比东财 5s
 * 放宽；重试 1 + 退避 500ms，分页 GET 幂等），日志标签 {@code sina-hq-node}。失败上抛 {@link
 * IllegalStateException}，调用方按市场级放弃处理。
 *
 * <p>端口契约：<b>仅支持 {@link MarketSyncSpec#A_SHARE_STOCK}</b>——新浪无港股节点（hk_stocks 实测返空，ADR-0027），
 * 港股/指数桶由路由层恒走东财。
 */
@Component
public class SinaSubjectListClient implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(SinaSubjectListClient.class);

    static final String DEFAULT_LIST_URL = DataSourceDefaults.SINA_STOCK_LIST_URL;

    /** 沪深 A 股全量节点（2026-09-22 实测含北交所 bj 前缀行，客户端按口径过滤）。 */
    private static final String NODE_HS_A = "hs_a";

    /** 按代码稳定排序（sort=symbol + asc=1），翻页期间结果集漂移最小化（对齐东财 fid=f12 策略）。 */
    private static final String SORT_FIELD_SYMBOL = "symbol";

    private static final int SORT_ASCENDING = 1;

    /** 新浪软限频要求的来源页（无 token，靠 Referer 标识来源，防 403——与 SinaNewsClient 同款）。 */
    static final String DEFAULT_REFERER = DataSourceDefaults.SINA_STOCK_REFERER;

    /** 浏览器 UA（新浪对裸 curl UA 易封，与 SinaNewsClient/EastMoneyHttpSupport 同款）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** 单页弹性（ADR-0030）：超时 10s（§3.1 实测新浪冷响应 6.8s）/ 重试 1 / 退避基数 500ms（分页 GET 幂等）。 */
    private static final ResilienceSpec PAGE_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(10), 1, Duration.ofMillis(500));

    /** 弹性日志标签（列表源非既有 SourceCode 枚举，与东财 clist 同口径不强接，ADR-0029 裁定 6）。 */
    private static final String SOURCE_LABEL = "sina-hq-node";

    private final RestClient restClient;
    private final ResilienceRunner resilienceRunner;
    private final String listUrl;
    private final int pageSize;
    private final long pageIntervalMillis;
    private final int maxPages;
    private final String referer;

    /**
     * Spring 装配构造（ADR-0032）：端点/Referer 取 {@link DataSourceDefaults} 代码内置缺省（原 {@code
     * adapter.sina.stock-*} 构造期缺省收口）；分页参数仍走 {@code subject.sync.*} yml（RESTART 级）。
     */
    @Autowired
    public SinaSubjectListClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            @Value("${subject.sync.page-size:100}") int pageSize,
            @Value("${subject.sync.page-interval-millis:600}") long pageIntervalMillis,
            @Value("${subject.sync.max-pages:200}") int maxPages) {
        this(
                restClientBuilder,
                resilienceRunner,
                DEFAULT_LIST_URL,
                pageSize,
                pageIntervalMillis,
                maxPages,
                DEFAULT_REFERER);
    }

    /** 全参构造（纯构造单测指定端点/Referer/分页参数）。 */
    public SinaSubjectListClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            String listUrl,
            int pageSize,
            long pageIntervalMillis,
            int maxPages,
            String referer) {
        this.restClient = restClientBuilder.build();
        this.resilienceRunner = resilienceRunner;
        this.listUrl = listUrl;
        this.pageSize = pageSize <= 0 ? 100 : pageSize;
        this.pageIntervalMillis = Math.max(0, pageIntervalMillis);
        this.maxPages = maxPages <= 0 ? 200 : maxPages;
        this.referer = referer;
    }

    @Override
    public List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket) {
        if (bucket != MarketSyncSpec.A_SHARE_STOCK) {
            throw new IllegalStateException("新浪列表源仅支持 A 股桶（无港股节点，ADR-0030）bucket=" + bucket);
        }
        Map<String, SubjectSnapshot> distinct = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            if (page > maxPages) {
                throw new IllegalStateException(
                        "新浪列表翻页超出防御上限 bucket="
                                + bucket
                                + " maxPages="
                                + maxPages
                                + " collected="
                                + distinct.size());
            }
            List<Map<String, Object>> rows = fetchPage(page);
            if (page == 1 && rows.isEmpty()) {
                // 防假空：首页即空（源故障/封禁返 []）不能当「全量为空」——否则整桶误判缺失（零写入由调用方放弃保证）
                throw new IllegalStateException("新浪列表首页为空（防假空，疑似源故障/封禁）bucket=" + bucket);
            }
            for (SubjectSnapshot snapshot : mapRows(rows, bucket, page)) {
                if (distinct.putIfAbsent(snapshot.subjectCode(), snapshot) != null) {
                    log.warn(
                            "新浪列表重复标的代码去重 bucket={} subjectCode={} page={}",
                            bucket,
                            snapshot.subjectCode(),
                            page);
                }
            }
            log.debug(
                    "新浪列表分页进度 bucket={} page={} 本页行数={} 累计={}",
                    bucket,
                    page,
                    rows.size(),
                    distinct.size());
            // 终止条件（无总数字段，ADR-0030）：空页 / 不足 num 的短页即最后一页
            if (rows.size() < pageSize) {
                break;
            }
            sleepBetweenPages(bucket, ++page);
        }
        if (distinct.isEmpty()) {
            // 兜底防御：映射后全量为空（如全被行级过滤）同样按假空放弃
            throw new IllegalStateException("新浪列表全量结果为空（防假空）bucket=" + bucket);
        }
        log.info("新浪列表全量拉取完成 bucket={} 行数={} 页数上限={}", bucket, distinct.size(), maxPages);
        return List.copyOf(distinct.values());
    }

    /** 单页拉取（ResilienceSpec 包裹），失败上抛由调用方按市场放弃；越界页返回字面量 null → 归一空列表。 */
    private List<Map<String, Object>> fetchPage(int page) {
        String url = buildUrl(page);
        try {
            List<Map<String, Object>> body =
                    resilienceRunner.run(
                            () ->
                                    restClient
                                            .get()
                                            .uri(url)
                                            .accept(MediaType.APPLICATION_JSON)
                                            .header("User-Agent", USER_AGENT)
                                            .header("Referer", referer)
                                            .retrieve()
                                            .body(
                                                    new ParameterizedTypeReference<
                                                            List<Map<String, Object>>>() {}),
                            PAGE_RESILIENCE,
                            SOURCE_LABEL);
            return body == null ? List.of() : body;
        } catch (ResilienceException e) {
            throw new IllegalStateException("新浪列表第 " + page + " 页拉取失败（重试耗尽）", e);
        }
    }

    private String buildUrl(int page) {
        return UriComponentsBuilder.fromUriString(listUrl)
                .queryParam("page", page)
                .queryParam("num", pageSize)
                .queryParam("sort", SORT_FIELD_SYMBOL)
                .queryParam("asc", SORT_ASCENDING)
                .queryParam("node", NODE_HS_A)
                .build()
                .toUriString();
    }

    /** 页间隔礼貌 sleep（与东财共用 subject.sync.page-interval-millis；被中断视为同步终止，上抛由调用方放弃）。 */
    private void sleepBetweenPages(MarketSyncSpec bucket, int nextPage) {
        if (pageIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pageIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "新浪列表页间隔被中断 bucket=" + bucket + " nextPage=" + nextPage, e);
        }
    }

    /** 整页映射：逐行 {@link #mapRow}（bj 跳过 / 异常行丢弃记 WARN），行级缺口无 total 校验兜底、由口径过滤性质决定可接受。 */
    private List<SubjectSnapshot> mapRows(
            List<Map<String, Object>> rows, MarketSyncSpec bucket, int page) {
        List<SubjectSnapshot> snapshots = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            mapRow(row, bucket, page).ifPresent(snapshots::add);
        }
        return snapshots;
    }

    /**
     * 单行映射（ADR-0030）：{@code symbol}（如 {@code sh600519}）前缀判市场、余部为代码；secid 沪 {@code 1.代码} / 深 {@code
     * 0.代码}（对齐东财快照，行情链路零改造）。
     *
     * <p>{@code bj} 前缀（北交所）静默跳过（口径外，非源异常）；空名/畸形 symbol 行丢弃记 WARN。
     */
    private Optional<SubjectSnapshot> mapRow(
            Map<String, Object> row, MarketSyncSpec bucket, int page) {
        String symbol = readString(row.get("symbol"));
        if (symbol.length() < 3) {
            log.warn("新浪列表行 symbol 缺失/畸形，丢弃 bucket={} page={} symbol={}", bucket, page, symbol);
            return Optional.empty();
        }
        String prefix = symbol.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
        String code = symbol.substring(2);
        int marketFlag;
        switch (prefix) {
            case "sh" -> marketFlag = 1;
            case "sz" -> marketFlag = 0;
            case "bj" -> {
                // 北交所不在需求口径（ADR-0027 fs 不含 bj）且 secid 派生规则未实测——跳过，不引入虚构主数据（ADR-0030）
                log.debug("新浪列表跳过北交所行 bucket={} page={} symbol={}", bucket, page, symbol);
                return Optional.empty();
            }
            default -> {
                log.warn(
                        "新浪列表行未知市场前缀，丢弃 bucket={} page={} prefix={} symbol={}",
                        bucket,
                        page,
                        prefix,
                        symbol);
                return Optional.empty();
            }
        }
        String name = readString(row.get("name")).trim();
        if (name.isEmpty()) {
            log.warn("新浪列表行名称为空，丢弃 bucket={} page={} symbol={}", bucket, page, symbol);
            return Optional.empty();
        }
        return Optional.of(
                new SubjectSnapshot(
                        MarketSyncSpec.codePrefixOf(marketFlag) + code,
                        name,
                        null,
                        marketFlag + "." + code,
                        bucket));
    }

    private static String readString(Object raw) {
        if (raw == null) {
            return "";
        }
        return raw instanceof Number number ? number.toString() : String.valueOf(raw).trim();
    }
}
