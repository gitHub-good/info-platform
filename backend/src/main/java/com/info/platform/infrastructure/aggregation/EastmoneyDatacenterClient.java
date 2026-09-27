package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.markettop.IndustryBoardSource;
import com.info.platform.infrastructure.common.ResilienceException;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 东财 datacenter {@code RPT_WEB_RESPREDICT} 行业板块 HTTP 客户端（M21 T180 通道 A，方案 §4.1.2 + ADR-0059 裁决
 * 1②）：实现应用层端口 {@link IndustryBoardSource}（SubjectListSource 同款依赖倒置——回填编排只依赖端口，换源只换实现）。
 *
 * <p>分页拉取全量机构覆盖标的的 {@code INDUSTRY_BOARD}（东财板块口径，2026-09-27 实测 2933 只 / 127 值域 / 30 页稳定）： columns
 * 只取 SECURITY_CODE+INDUSTRY_BOARD（响应最小化，非估值/因子数据不入库——方案 §1.6 非目标红线）、页大小 100、页间隔 500ms（实测礼貌值）、{@code
 * result.count} 完整性校验（累计行数 ≠ count 抛异常 → 调用方降级继续）。单页弹性沿 {@link EastMoneyListClient}（超时 5s / 重试
 * 1）；共用约定复用 {@link EastMoneyHttpSupport}（浏览器 UA 防 WAF 断连 + {@code text/plain} JSON 容错读）+ {@code
 * Referer: https://data.eastmoney.com/}（datacenter 软限频要求，实测缺 Referer 不可用）。日志标签 {@code
 * eastmoney-respredict}（非 SourceCode 枚举，不强接）。
 */
@Component
public class EastmoneyDatacenterClient implements IndustryBoardSource {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyDatacenterClient.class);

    /** datacenter 报表端点（与 EastMoneyFinanceClient 同端点，EastMoneyListClient 缺省 URL 同款内置惯例）。 */
    static final String DEFAULT_REPORT_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";

    /** 行业板块报表名（API 契约常量，Spike-B 验证 + 2026-09-27 全量复测）。 */
    static final String REPORT_NAME = "RPT_WEB_RESPREDICT";

    /** 只取两列（代码 + 板块）——响应最小化。 */
    static final String REPORT_COLUMNS = "SECURITY_CODE,INDUSTRY_BOARD";

    /** 按代码稳定排序，翻页期间结果集漂移最小化（fid=f12 同因）。 */
    private static final String SORT_COLUMN = "SECURITY_CODE";

    private static final int SORT_ASCENDING = 1;

    /** 页大小（datacenter 常规上限，与 clist pageSize 同值）。 */
    private static final int PAGE_SIZE = 100;

    /** 单页弹性（clist 同款：超时 5s / 重试 1 / 退避 500ms——分页 GET 幂等）。 */
    private static final ResilienceSpec PAGE_RESILIENCE =
            ResilienceSpec.of(Duration.ofSeconds(5), 1, Duration.ofMillis(500));

    /** 弹性日志标签（datacenter 报表源，非 SourceCode 枚举）。 */
    private static final String SOURCE_LABEL = "eastmoney-respredict";

    /** 翻页防御上限（实测 30 页；3× 裕量防源侧翻页参数漂移）。 */
    private static final int MAX_PAGES = 100;

    private final RestClient restClient;
    private final ResilienceRunner resilienceRunner;
    private final String reportUrl;
    private final long pageIntervalMillis;

    /** Spring 装配构造（页间隔 500ms——方案 §4.1.2 实测礼貌值）。 */
    @Autowired
    public EastmoneyDatacenterClient(
            RestClient.Builder restClientBuilder, ResilienceRunner resilienceRunner) {
        this(restClientBuilder, resilienceRunner, DEFAULT_REPORT_URL, 500L);
    }

    /** 全参构造（纯构造单测指定端点与页间隔）。 */
    public EastmoneyDatacenterClient(
            RestClient.Builder restClientBuilder,
            ResilienceRunner resilienceRunner,
            String reportUrl,
            long pageIntervalMillis) {
        this.restClient = EastMoneyHttpSupport.withTextPlainJson(restClientBuilder).build();
        this.resilienceRunner = resilienceRunner;
        this.reportUrl = reportUrl;
        this.pageIntervalMillis = Math.max(0, pageIntervalMillis);
    }

    @Override
    public List<IndustryBoardRow> fetchIndustryBoards() {
        Map<String, IndustryBoardRow> distinct = new LinkedHashMap<>();
        int total = -1;
        int pageNumber = 1;
        while (true) {
            if (pageNumber > MAX_PAGES) {
                throw new IllegalStateException(
                        "respredict 翻页超出防御上限 maxPages="
                                + MAX_PAGES
                                + " collected="
                                + distinct.size()
                                + " total="
                                + total);
            }
            Page page = parsePage(fetchPage(pageNumber), pageNumber);
            if (total < 0) {
                total = page.total();
            }
            for (IndustryBoardRow row : page.rows()) {
                if (distinct.putIfAbsent(row.securityCode(), row) != null) {
                    log.warn(
                            "respredict 重复证券代码去重 subjectCode={} page={}",
                            row.securityCode(),
                            pageNumber);
                }
            }
            log.debug(
                    "respredict 分页进度 page={} 本页行数={} 累计={} total={}",
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
                    "respredict count 完整性校验失败 collected="
                            + distinct.size()
                            + " total="
                            + total
                            + "（丢页/空页/异常行，本轮回填通道放弃）");
        }
        log.info("respredict 全量拉取完成 rows={} pages<={}", total, MAX_PAGES);
        return List.copyOf(distinct.values());
    }

    /** 单页拉取（ResilienceSpec 包裹），失败上抛由调用方按通道降级处理。 */
    private Map<String, Object> fetchPage(int pageNumber) {
        String url = buildUrl(pageNumber);
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
            throw new IllegalStateException("respredict 第 " + pageNumber + " 页拉取失败（重试耗尽）", e);
        }
    }

    /** 手工拼接 query 串（EastMoneyFinanceClient 同因：UriComponentsBuilder 对已编码参数行为不稳）。 */
    private String buildUrl(int pageNumber) {
        return reportUrl
                + "?reportName="
                + REPORT_NAME
                + "&columns="
                + REPORT_COLUMNS
                + "&pageNumber="
                + pageNumber
                + "&pageSize="
                + PAGE_SIZE
                + "&sortColumns="
                + SORT_COLUMN
                + "&sortTypes="
                + SORT_ASCENDING;
    }

    /** 页间隔礼貌 sleep（被中断视为回填终止，上抛由调用方降级）。 */
    private void sleepBetweenPages(int nextPage) {
        if (pageIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pageIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("respredict 页间隔被中断 nextPage=" + nextPage, e);
        }
    }

    /** 解析单页：{@code result.count} + {@code result.data[]}；result 节点缺失/count 非法 → 本轮通道放弃。 */
    private Page parsePage(Map<String, Object> body, int pageNumber) {
        if (body == null || !(body.get("result") instanceof Map<?, ?> result)) {
            throw new IllegalStateException(
                    "respredict 响应缺 result 节点 page=" + pageNumber + "（本轮回填通道放弃）");
        }
        Object rawCount = result.get("count");
        if (!(rawCount instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException(
                    "respredict 响应 count 非法 page=" + pageNumber + " count=" + rawCount);
        }
        List<IndustryBoardRow> rows = new ArrayList<>();
        if (result.get("data") instanceof List<?> data) {
            for (Object item : data) {
                if (item instanceof Map<?, ?> row) {
                    mapRow(row).ifPresent(rows::add);
                }
            }
        }
        return new Page(number.intValue(), rows);
    }

    private java.util.Optional<IndustryBoardRow> mapRow(Map<?, ?> row) {
        Object code = row.get("SECURITY_CODE");
        if (code == null || String.valueOf(code).isBlank()) {
            log.warn("respredict 行缺 SECURITY_CODE，丢弃");
            return java.util.Optional.empty();
        }
        Object board = row.get("INDUSTRY_BOARD");
        if (board == null || String.valueOf(board).isBlank()) {
            return java.util.Optional.empty(); // 无板块行静默跳过（计数缺口由 count 完整性校验兜底）
        }
        return java.util.Optional.of(
                new IndustryBoardRow(String.valueOf(code), String.valueOf(board)));
    }

    /** 单页解析结果：源声明总数 + 已映射行。 */
    private record Page(int total, List<IndustryBoardRow> rows) {}
}
