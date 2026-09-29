package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.domain.analysis.IndustryEnumMapper;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * 东财 datacenter F10 档案全量列表 HTTP 客户端（M29 T251 建池主通道，方案 §4 C1 / ADR-0064 裁决 3——push2 clist 封禁期的
 * 港美股建池承载，仿 {@link EastmoneyDatacenterClient} 同端点/Referer/分页/弹性封装）。
 *
 * <p>实现应用层端口 {@link SubjectListSource} 的港股/美股两桶：{@code RPT_HKF10_INFO_ORGPROFILE}（全量 6961）/ {@code
 * RPT_USF10_INFO_ORGPROFILE}（全量
 * 21561），columns=SECUCODE,SECURITY_NAME_ABBR,BELONG_INDUSTRY（一次拉取双用途：建池 + 行业回填，Spike-E E-1b
 * 实测契约）、{@code sortColumns=SECUCODE} 稳定排序防翻页漂移、500/页（方案 §8 压测惯例，首跑 2026-09-29 实测 500/页稳定：港 14 页 / 美
 * 44 页）、页间隔 ≥500ms 礼貌限速。
 *
 * <p>桶过滤规则（方案 §2.3 ①，桶语义 =「数据源 + 过滤规则」）：港股 = SECUCODE 代码段 5 位纯数字（剔 {@code .CMU} 基金等非股票 1193 行）+ 行业
 * null 容忍 → {@code UNKNOWN}；美股 = 后缀 ∈ {@code .N}(NYSE)/{@code .O}(NASDAQ) 主板（剔 {@code .F} OTC 15294
 * 行与 {@code .A} AMEX 270 行）+ 行业非空预筛（剔 28 行无行业主板股）。建池量级（2026-09-29 首跑）：港 5768 / 美 5969。
 *
 * <p>secid 派生（Spike-E §5.2）：港 {@code 116.00700}（代码段 5 位数字，与 V2 种子先例一致）；美 {@code 105.AAPL}（NASDAQ
 * {@code .O}）/ {@code 106.A}(NYSE {@code .N})，subject_code = {@code US} + 大写 ticker。行业经 {@link
 * IndustryEnumMapper} 归并（港 31 直采 / 美 156 词归并 40 大类，null/未收录 → UNKNOWN）。
 *
 * <p>total 完整性校验：分页累计的<b>原始行数</b>（过滤前）≠ {@code result.count} 抛异常 → 调用方该市场整轮放弃（零写入，防丢页假缺
 * 失）；过滤剔除是规则内行为不计入缺口。单页弹性：超时 10s（500 行/页响应体较大，较 clist 100 行 5s 放宽）/ 重试 1 / 退避 500ms （分页 GET
 * 幂等）。共用约定复用 {@link EastMoneyHttpSupport}（浏览器 UA + {@code text/plain} JSON 容错读）+ {@code Referer:
 * https://data.eastmoney.com/}（datacenter 软限频要求，实测缺 Referer 不可用）。日志标签 {@code eastmoney-f10}（非
 * SourceCode 枚举，不强接）。
 */
@Component
public class EastMoneyF10ListClient implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyF10ListClient.class);

    /** F10 报表端点（Spike-E E-1b 实测宿主，securities 路径与 datacenter-web 报表端点不同）。 */
    static final String DEFAULT_F10_URL =
            "https://datacenter.eastmoney.com/securities/api/data/v1/get";

    /** 港股 F10 公司档案报表名（API 契约常量，Spike-E E-1b 验证 + 2026-09-29 全量复测）。 */
    static final String HK_REPORT_NAME = "RPT_HKF10_INFO_ORGPROFILE";

    /** 美股 F10 公司档案报表名（同上）。 */
    static final String US_REPORT_NAME = "RPT_USF10_INFO_ORGPROFILE";

    /** 只取三列（代码 / 简称 / 行业）——响应最小化（建池 + 行业回填双用途，方案 §4 C1）。 */
    static final String REPORT_COLUMNS = "SECUCODE,SECURITY_NAME_ABBR,BELONG_INDUSTRY";

    /** 按代码稳定排序升序，翻页期间结果集漂移最小化。 */
    private static final String SORT_COLUMN = "SECUCODE";

    private static final int SORT_ASCENDING = 1;

    /** 页大小缺省 500（方案 §8 压测惯例；港 14 页 / 美 44 页）。 */
    private static final int DEFAULT_PAGE_SIZE = 500;

    /** 页间隔缺省 500ms（方案建池限流：请求间隔 ≥500ms）。 */
    private static final long DEFAULT_PAGE_INTERVAL_MILLIS = 500L;

    /** 翻页防御上限缺省（实测美 44 页；3× 裕量防源侧翻页参数漂移）。 */
    private static final int DEFAULT_MAX_PAGES = 150;

    /** 单页弹性：超时 10s / 重试 1 / 退避 500ms（分页 GET 幂等可重试）。 */
    private static final ResilienceSpec PAGE_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(10), 1, Duration.ofMillis(500));

    /** 弹性日志标签（F10 列表源，非 SourceCode 枚举）。 */
    private static final String SOURCE_LABEL = "eastmoney-f10";

    /** 港股代码段长度（5 位纯数字，剔 .CMU 基金等非 5 位代码）。 */
    private static final int HK_CODE_LENGTH = 5;

    private final RestClient restClient;
    private final ResilienceRunner resilienceRunner;
    private final String reportUrl;
    private final int pageSize;
    private final long pageIntervalMillis;
    private final int maxPages;

    /** Spring 装配构造（分页参数走 {@code subject.sync.f10-*} yml，缺省即方案礼貌值）。 */
    @Autowired
    public EastMoneyF10ListClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            @Value("${subject.sync.f10-page-size:500}") int pageSize,
            @Value("${subject.sync.f10-page-interval-millis:500}") long pageIntervalMillis,
            @Value("${subject.sync.f10-max-pages:150}") int maxPages) {
        this(
                restClientBuilder,
                resilienceRunner,
                DEFAULT_F10_URL,
                pageSize,
                pageIntervalMillis,
                maxPages);
    }

    /** 全参构造（纯构造单测指定端点与分页参数）。 */
    public EastMoneyF10ListClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            String reportUrl,
            int pageSize,
            long pageIntervalMillis,
            int maxPages) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.reportUrl = reportUrl;
        this.pageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        this.pageIntervalMillis = Math.max(0, pageIntervalMillis);
        this.maxPages = maxPages <= 0 ? DEFAULT_MAX_PAGES : maxPages;
    }

    @Override
    public List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket) {
        if (bucket != MarketSyncSpec.HK_STOCK && bucket != MarketSyncSpec.US_STOCK) {
            throw new IllegalArgumentException("F10 列表客户端仅承载港美股桶，收到: " + bucket);
        }
        return bucket == MarketSyncSpec.HK_STOCK ? fetchHk(bucket) : fetchUs(bucket);
    }

    /** 港股桶：5 位纯数字代码段过滤（行业 null 容忍 → UNKNOWN 枚举）。 */
    private List<SubjectSnapshot> fetchHk(MarketSyncSpec bucket) {
        List<F10Row> rows = fetchAllRows(HK_REPORT_NAME, bucket);
        Map<String, SubjectSnapshot> distinct = new LinkedHashMap<>();
        int filtered = 0;
        for (F10Row row : rows) {
            String[] parts = splitSecuCode(row.secucode());
            if (parts == null || !".HK".equals(parts[1]) || !isFiveDigit(parts[0])) {
                filtered++;
                continue;
            }
            mapRow(row, parts[0], "116", bucket)
                    .ifPresent(snapshot -> distinct.putIfAbsent(snapshot.subjectCode(), snapshot));
        }
        log.info(
                "F10 港股全量拉取完成 rows={} 入池={} 剔除非5位代码段={}（.CMU 基金等非股票）",
                rows.size(),
                distinct.size(),
                filtered);
        return List.copyOf(distinct.values());
    }

    /** 美股桶：{@code .N}/.{@code .O} 主板后缀 + 行业非空双预筛（剔 OTC/AMEX/无行业长尾）。 */
    private List<SubjectSnapshot> fetchUs(MarketSyncSpec bucket) {
        List<F10Row> rows = fetchAllRows(US_REPORT_NAME, bucket);
        Map<String, SubjectSnapshot> distinct = new LinkedHashMap<>();
        int suffixFiltered = 0;
        int industryFiltered = 0;
        for (F10Row row : rows) {
            String[] parts = splitSecuCode(row.secucode());
            String marketFlag;
            if (parts != null && ".O".equals(parts[1])) {
                marketFlag = "105";
            } else if (parts != null && ".N".equals(parts[1])) {
                marketFlag = "106";
            } else {
                suffixFiltered++;
                continue;
            }
            if (row.industry() == null || row.industry().isBlank()) {
                industryFiltered++;
                continue;
            }
            mapRow(row, parts[0], marketFlag, bucket)
                    .ifPresent(snapshot -> distinct.putIfAbsent(snapshot.subjectCode(), snapshot));
        }
        log.info(
                "F10 美股全量拉取完成 rows={} 入池={} 剔除非主板后缀={}（.F OTC/.A AMEX 等） 剔除无行业={}",
                rows.size(),
                distinct.size(),
                suffixFiltered,
                industryFiltered);
        return List.copyOf(distinct.values());
    }

    /** 分页遍历全量原始行（过滤前）+ count 完整性校验（EastmoneyDatacenterClient 同构）。 */
    private List<F10Row> fetchAllRows(String reportName, MarketSyncSpec bucket) {
        Map<String, F10Row> distinct = new LinkedHashMap<>();
        int total = -1;
        int pageNumber = 1;
        while (true) {
            if (pageNumber > maxPages) {
                throw new IllegalStateException(
                        "F10 翻页超出防御上限 bucket="
                                + bucket
                                + " reportName="
                                + reportName
                                + " maxPages="
                                + maxPages
                                + " collected="
                                + distinct.size()
                                + " total="
                                + total);
            }
            Page page = parsePage(fetchPage(reportName, pageNumber), bucket, pageNumber);
            if (total < 0) {
                total = page.total();
            }
            for (F10Row row : page.rows()) {
                if (distinct.putIfAbsent(row.secucode(), row) != null) {
                    log.warn(
                            "F10 重复 SECUCODE 去重 bucket={} secucode={} page={}",
                            bucket,
                            row.secucode(),
                            pageNumber);
                }
            }
            log.debug(
                    "F10 分页进度 bucket={} page={} 本页行数={} 累计={} total={}",
                    bucket,
                    pageNumber,
                    page.rows().size(),
                    distinct.size(),
                    total);
            if (page.rows().isEmpty() || distinct.size() >= total) {
                break;
            }
            sleepBetweenPages(++pageNumber);
        }
        if (distinct.size() != total) {
            throw new IllegalStateException(
                    "F10 count 完整性校验失败 bucket="
                            + bucket
                            + " collected="
                            + distinct.size()
                            + " total="
                            + total
                            + "（丢页/空页/异常行，该市场本轮放弃）");
        }
        return List.copyOf(distinct.values());
    }

    /** 单页拉取（ResilienceSpec 包裹），失败上抛由调用方按市场级放弃处理。 */
    private Map<String, Object> fetchPage(String reportName, int pageNumber) {
        String url = buildUrl(reportName, pageNumber);
        try {
            return resilienceRunner.run(
                    () ->
                            restClient
                                    .get()
                                    .uri(url)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .header("User-Agent", EastMoneyHttpSupport.USER_AGENT)
                                    .header("Referer", "https://data.eastmoney.com/")
                                    .retrieve()
                                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}),
                    PAGE_RESILIENCE,
                    SOURCE_LABEL);
        } catch (ResilienceException e) {
            throw new IllegalStateException(
                    "F10 第 " + pageNumber + " 页拉取失败（重试耗尽）reportName=" + reportName, e);
        }
    }

    /** 手工拼接 query 串（EastmoneyDatacenterClient 同因：UriComponentsBuilder 对已编码参数行为不稳）。 */
    private String buildUrl(String reportName, int pageNumber) {
        return reportUrl
                + "?reportName="
                + reportName
                + "&columns="
                + REPORT_COLUMNS
                + "&pageNumber="
                + pageNumber
                + "&pageSize="
                + pageSize
                + "&sortColumns="
                + SORT_COLUMN
                + "&sortTypes="
                + SORT_ASCENDING
                + "&source=F10&client=PC";
    }

    /** 页间隔礼貌 sleep（被中断视为同步终止，上抛由调用方放弃该市场）。 */
    private void sleepBetweenPages(int nextPage) {
        if (pageIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pageIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("F10 页间隔被中断 nextPage=" + nextPage, e);
        }
    }

    /** 解析单页：{@code result.count} + {@code result.data[]}；result 缺失/count 非法 → 该市场放弃。 */
    private Page parsePage(Map<String, Object> body, MarketSyncSpec bucket, int pageNumber) {
        if (body == null || !(body.get("result") instanceof Map<?, ?> result)) {
            throw new IllegalStateException(
                    "F10 响应缺 result 节点 bucket=" + bucket + " page=" + pageNumber + "（该市场本轮放弃）");
        }
        Object rawCount = result.get("count");
        if (!(rawCount instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException(
                    "F10 响应 count 非法 bucket="
                            + bucket
                            + " page="
                            + pageNumber
                            + " count="
                            + rawCount);
        }
        List<F10Row> rows = new ArrayList<>();
        if (result.get("data") instanceof List<?> data) {
            for (Object item : data) {
                if (item instanceof Map<?, ?> row) {
                    mapRawRow(row).ifPresent(rows::add);
                }
            }
        }
        return new Page(number.intValue(), rows);
    }

    /** 原始行映射：SECUCODE/SECURITY_NAME_ABBR 必填（缺失丢弃记 WARN，缺口由 count 完整性校验兜底）。 */
    private Optional<F10Row> mapRawRow(Map<?, ?> row) {
        String secucode = readString(row.get("SECUCODE"));
        if (secucode.isEmpty()) {
            log.warn("F10 行缺 SECUCODE，丢弃");
            return Optional.empty();
        }
        String name = readString(row.get("SECURITY_NAME_ABBR")).trim();
        if (name.isEmpty()) {
            log.warn("F10 行名称为空，丢弃 secucode={}", secucode);
            return Optional.empty();
        }
        String industry = readString(row.get("BELONG_INDUSTRY")).trim();
        return Optional.of(new F10Row(secucode, name, industry.isEmpty() ? null : industry));
    }

    /** 过滤后行构造：行业枚举归并（null/未收录 → UNKNOWN）+ subject_code/secid/f10 三键派生。 */
    private static Optional<SubjectSnapshot> mapRow(
            F10Row row, String rawCode, String marketFlag, MarketSyncSpec bucket) {
        String ticker =
                bucket == MarketSyncSpec.US_STOCK ? rawCode.toUpperCase(Locale.ROOT) : rawCode;
        String secid = marketFlag + "." + ticker;
        return Optional.of(
                new SubjectSnapshot(
                        MarketSyncSpec.codePrefixOf(Integer.parseInt(marketFlag)) + ticker,
                        row.name(),
                        IndustryEnumMapper.map(bucket.market(), row.industry()),
                        secid,
                        bucket,
                        row.secucode()));
    }

    /** SECUCODE 拆分为 {@code [代码段, .后缀]}；无后缀/空段 → null（非法形态由桶过滤剔除）。 */
    private static String[] splitSecuCode(String secucode) {
        int dot = secucode.lastIndexOf('.');
        if (dot <= 0 || dot == secucode.length() - 1) {
            return null;
        }
        return new String[] {secucode.substring(0, dot), secucode.substring(dot)};
    }

    private static boolean isFiveDigit(String code) {
        return code.length() == HK_CODE_LENGTH && code.chars().allMatch(Character::isDigit);
    }

    private static String readString(Object raw) {
        if (raw == null) {
            return "";
        }
        return raw instanceof Number number ? number.toString() : String.valueOf(raw).trim();
    }

    /** F10 原始行（过滤前形态，行业 null 容忍——美股桶在过滤阶段剔、港股桶归 UNKNOWN）。 */
    private record F10Row(String secucode, String name, String industry) {}

    /** 单页解析结果：源声明总数 + 已映射原始行。 */
    private record Page(int total, List<F10Row> rows) {}
}
