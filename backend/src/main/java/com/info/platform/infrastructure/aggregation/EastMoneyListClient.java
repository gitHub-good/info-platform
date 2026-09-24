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
 * 东财 push2 {@code clist/get} 全量列表 HTTP 客户端（T50，技术方案增补 §4.2，ADR-0027 主选源）。
 *
 * <p>实现应用层端口 {@link SubjectListSource}（依赖倒置，先例 {@code DataSourceConfigFacade}）——同步引擎只依赖端口，
 * 连续多日失败切备选源（ADR-0027）时只换实现类，引擎零改动。
 *
 * <p>分页拉取市场桶全量标的：页大小 100（服务端强制上限，实测 pz&gt;100 仍只回 100 行）、页间礼貌 sleep（默认 600ms——实测 300~400ms 连续约 28
 * 次触发 IP 级封禁，ADR-0027）、{@code fid=f12} 稳定排序防翻页漂移、<b>total 完整性校验</b>（累计行数≠total 抛异常 →
 * 调用方整轮放弃该市场，防丢页假缺失）。
 *
 * <p>字段映射（§4.2 映射表）：f12 代码 / f13 市场（恰为 secid 前缀，{@code secid = f13 + "." + f12}）/ f14
 * 名称（trim，空名行丢弃记 WARN）/ f100 行业（{@code "-"} 占位 → null）。共用约定复用 {@link EastMoneyHttpSupport}（浏览器 UA
 * 防断连 + {@code text/plain} JSON 容错读）。
 *
 * <p>单页弹性由 {@link ResilienceRunner} 包裹（超时 5s / 重试 1 / 退避 500ms——分页 GET 幂等可重试，§4.2 参数表）； 重试耗尽仍败 → 抛
 * {@link IllegalStateException}，调用方按市场级放弃处理（本客户端不降级、不吞异常）。 日志标签 {@code eastmoney-clist}：clist
 * 为列表源，非既有七源 {@code SourceCode} 枚举，不强接（§5 可观测裁定）。
 */
@Component
public class EastMoneyListClient implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyListClient.class);

    static final String DEFAULT_LIST_URL = DataSourceDefaults.EASTMONEY_LIST_URL;

    /** 东财返回格式契约常量（与 EastMoneyClient 同款）：fltt=2 带小数、invt=2 单位元、np=1 网页原生参数。 */
    private static final int PRICE_FMT_DECIMAL = 2;

    private static final int PRICE_UNIT_YUAN = 2;

    /** 按代码降序稳定排序（po=1 + fid=f12），翻页期间结果集漂移最小化（§4.2）。 */
    private static final int SORT_DESCENDING = 1;

    private static final String SORT_FIELD_CODE = "f12";

    /** 只取 4 列（代码/市场/名称/行业），响应最小（§4.2 fields）。 */
    private static final String LIST_FIELDS = "f12,f13,f14,f100";

    /** 港股无行业时源侧的实测占位值 → 归一 null（§4.2 映射表）。 */
    private static final String INDUSTRY_PLACEHOLDER = "-";

    /** 单页弹性（§4.2 参数表）：超时 5s / 重试 1 / 退避基数 500ms（分页 GET 幂等）。 */
    private static final ResilienceSpec PAGE_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(5), 1, Duration.ofMillis(500));

    /** 弹性日志标签（非既有 SourceCode 枚举的列表源，见类注释）。 */
    private static final String SOURCE_LABEL = "eastmoney-clist";

    private final RestClient restClient;
    private final ResilienceRunner resilienceRunner;
    private final String listUrl;
    private final int pageSize;
    private final long pageIntervalMillis;
    private final int maxPages;

    /**
     * Spring 装配构造（ADR-0032）：列表端点取 {@link DataSourceDefaults#EASTMONEY_LIST_URL} 代码内置缺省（原 yml {@code
     * adapter.eastmoney.list-url} 迁移）；分页参数仍走 {@code subject.sync.*} yml（RESTART 级）。
     */
    @Autowired
    public EastMoneyListClient(
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
                maxPages);
    }

    /** 全参构造（纯构造单测指定端点与分页参数）。 */
    public EastMoneyListClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            String listUrl,
            int pageSize,
            long pageIntervalMillis,
            int maxPages) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.listUrl = listUrl;
        this.pageSize = pageSize <= 0 ? 100 : pageSize;
        this.pageIntervalMillis = Math.max(0, pageIntervalMillis);
        this.maxPages = maxPages <= 0 ? 200 : maxPages;
    }

    @Override
    public List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket) {
        Map<String, SubjectSnapshot> distinct = new LinkedHashMap<>();
        int total = -1;
        int pn = 1;
        while (true) {
            if (pn > maxPages) {
                throw new IllegalStateException(
                        "clist 翻页超出防御上限 bucket="
                                + bucket
                                + " maxPages="
                                + maxPages
                                + " collected="
                                + distinct.size()
                                + " total="
                                + total);
            }
            Page page = parsePage(fetchPage(bucket, pn), bucket, pn);
            if (total < 0) {
                total = page.total();
            }
            for (SubjectSnapshot snapshot : page.rows()) {
                if (distinct.putIfAbsent(snapshot.subjectCode(), snapshot) != null) {
                    // 翻页期源结果集漂移的防御：重复 code 去重留痕，行数缺口交由 total 校验决定成败（§7 风险表）
                    log.warn(
                            "clist 重复标的代码去重 bucket={} subjectCode={} page={}",
                            bucket,
                            snapshot.subjectCode(),
                            pn);
                }
            }
            log.debug(
                    "clist 分页进度 bucket={} page={} 本页行数={} 累计={} total={}",
                    bucket,
                    pn,
                    page.rows().size(),
                    distinct.size(),
                    total);
            if (page.rows().isEmpty() || distinct.size() >= total) {
                break;
            }
            sleepBetweenPages(bucket, ++pn);
        }
        if (distinct.size() != total) {
            throw new IllegalStateException(
                    "clist total 完整性校验失败 bucket="
                            + bucket
                            + " collected="
                            + distinct.size()
                            + " total="
                            + total
                            + "（丢页/空页/异常行，该市场本轮放弃）");
        }
        log.info("clist 全量拉取完成 bucket={} 行数={} 页数上限={}", bucket, total, maxPages);
        return List.copyOf(distinct.values());
    }

    /** 单页拉取（ResilienceSpec 包裹：超时 5s / 重试 1 / 退避 500ms），失败上抛由调用方按市场放弃。 */
    private Map<String, Object> fetchPage(MarketSyncSpec bucket, int pn) {
        String url = buildUrl(bucket.fs(), pn);
        try {
            return resilienceRunner.run(
                    () ->
                            restClient
                                    .get()
                                    .uri(url)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                    PAGE_RESILIENCE,
                    SOURCE_LABEL);
        } catch (ResilienceException e) {
            throw new IllegalStateException("clist 第 " + pn + " 页拉取失败（重试耗尽）bucket=" + bucket, e);
        }
    }

    private String buildUrl(String fs, int pn) {
        return UriComponentsBuilder.fromUriString(listUrl)
                .queryParam("pn", pn)
                .queryParam("pz", pageSize)
                .queryParam("po", SORT_DESCENDING)
                .queryParam("np", 1)
                .queryParam("fltt", PRICE_FMT_DECIMAL)
                .queryParam("invt", PRICE_UNIT_YUAN)
                .queryParam("fid", SORT_FIELD_CODE)
                .queryParam("fs", fs)
                .queryParam("fields", LIST_FIELDS)
                .build()
                .toUriString();
    }

    /** 页间隔礼貌 sleep（仅页与页之间；被中断视为同步终止，上抛由调用方放弃该市场）。 */
    private void sleepBetweenPages(MarketSyncSpec bucket, int nextPage) {
        if (pageIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pageIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "clist 页间隔被中断 bucket=" + bucket + " nextPage=" + nextPage, e);
        }
    }

    /** 解析单页：{@code data.total} + {@code data.diff[]}；data 节点缺失/total 非法 → 放弃该市场。 */
    private Page parsePage(Map<String, Object> body, MarketSyncSpec bucket, int pn) {
        if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException(
                    "clist 响应缺 data 节点 bucket=" + bucket + " page=" + pn + "（该市场本轮放弃）");
        }
        int total = readTotal(data, bucket, pn);
        List<SubjectSnapshot> rows = new ArrayList<>();
        if (data.get("diff") instanceof List<?> diff) {
            for (Object item : diff) {
                if (item instanceof Map<?, ?> row) {
                    mapRow(row, bucket, pn).ifPresent(rows::add);
                }
            }
        }
        return new Page(total, rows);
    }

    private int readTotal(Map<?, ?> data, MarketSyncSpec bucket, int pn) {
        Object raw = data.get("total");
        if (!(raw instanceof Number number) || number.intValue() <= 0) {
            // 真实股票桶 total 为千级；total<=0/缺失视为源异常（防把「空名单」当全量，误伤整桶标的）
            throw new IllegalStateException(
                    "clist 响应 total 非法 bucket=" + bucket + " page=" + pn + " total=" + raw);
        }
        return number.intValue();
    }

    /**
     * 单行映射（§4.2 字段映射表）：f12 代码 / f13 市场 / f14 名称 / f100 行业。
     *
     * <p>异常行（缺代码 / 未知市场码 / 空名）丢弃记 WARN——行数缺口由 total 完整性校验兜底（该市场放弃，不写半截数据）。
     */
    private Optional<SubjectSnapshot> mapRow(Map<?, ?> row, MarketSyncSpec bucket, int pn) {
        String code = readString(row.get("f12"));
        if (code.isEmpty()) {
            log.warn("clist 行缺代码 f12，丢弃 bucket={} page={}", bucket, pn);
            return Optional.empty();
        }
        if (!(row.get("f13") instanceof Number flag)) {
            log.warn("clist 行缺市场码 f13，丢弃 bucket={} page={} code={}", bucket, pn, code);
            return Optional.empty();
        }
        String name = readString(row.get("f14")).trim();
        if (name.isEmpty()) {
            log.warn("clist 行名称为空，丢弃 bucket={} page={} code={}", bucket, pn, code);
            return Optional.empty();
        }
        String prefix;
        try {
            prefix = MarketSyncSpec.codePrefixOf(flag.intValue());
        } catch (IllegalArgumentException e) {
            log.warn("clist 行未知市场码，丢弃 bucket={} page={} f13={} code={}", bucket, pn, flag, code);
            return Optional.empty();
        }
        return Optional.of(
                new SubjectSnapshot(
                        prefix + code,
                        name,
                        normalizeIndustry(readString(row.get("f100"))),
                        flag.intValue() + "." + code,
                        bucket));
    }

    private static String readString(Object raw) {
        if (raw == null) {
            return "";
        }
        return raw instanceof Number number ? number.toString() : String.valueOf(raw).trim();
    }

    private static String normalizeIndustry(String raw) {
        String trimmed = raw.trim();
        return trimmed.isEmpty() || INDUSTRY_PLACEHOLDER.equals(trimmed) ? null : trimmed;
    }

    /** 单页解析结果：源声明总数 + 已映射快照行。 */
    private record Page(int total, List<SubjectSnapshot> rows) {}
}
