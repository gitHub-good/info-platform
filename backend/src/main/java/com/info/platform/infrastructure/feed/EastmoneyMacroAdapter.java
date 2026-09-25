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
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 东财宏观指标预置适配器（M14 T110，REQ-20260925-11 拍板一 #10）：datacenter {@code reportName} 字典化拉取月度指标序列，
 * 序列行条目化为资讯流条目（映射裁量记录）：
 *
 * <ul>
 *   <li>externalId = {@code reportName#REPORT_DATE 日期段}——同指标同期次稳定（源侧修订值不另起条目，月度序列修订罕见且
 *       「一期一条」正是事件语义）；重复轮由 (source_id, external_id) 唯一索引幂等吸收
 *   <li>title = "{@code 指标名：TIME 中文期次 同比 X%}"（TIME 为源侧中文期次标签，2026-09-25 实测口径）
 *   <li>summary = 全国同比/环比/累计 + 城乡同比（列缺失逐段降级，不硬拼 null）
 *   <li>publishedAt = {@code REPORT_DATE}（墙钟按 Asia/Shanghai）——<b>数据期而非发布时刻</b>：源侧行不含发布时间，
 *       感知延迟口径对该源失真属已知取舍（大盘聚合口径归 T114）
 *   <li>cursorType=NONE（目录声明）：跨指标 TIME 游标会因期次日口径差异（月初/月末）误判已见漏新行，月度增量靠 双层唯一索引去重（每轮全量 20 行重拉，OR
 *       IGNORE 吸收，量级 24 轮/天×20 行可忽略）
 * </ul>
 *
 * <p>实测口径（2026-09-25 预检留档，ADR-0042 模式）：默认序为最旧在前，须显式 {@code sortColumns=REPORT_DATE&sortTypes=-1}；无
 * sortColumns 亦可取数（census 口径）但为首屏最旧 20 行； 排序列名 {@code DATE} 报 9501「排序列不存在」。robots：datacenter-web 返回
 * JSON 错误页（无 robots 文件）→ 按 RFC 9309 无限制。
 *
 * <p>字典首批仅 {@code RPT_ECONOMY_CPI}（唯一实测过的 reportName，ADR-0042「先实测再写映射」红线）；PPI/PMI/社融等 同端点扩展 = 字典加行
 * + 预检实测，无需结构改动。单报表取数失败（{@code success=false}）记 WARN 跳过续跑其余报表， 全部报表失败抛 {@link
 * FeedFetchException}（走退避，不吞异常）。
 */
@Component(EastmoneyMacroAdapter.BEAN_NAME)
public class EastmoneyMacroAdapter implements PresetFeedAdapter {

    /** 目录 {@code adapter_ref} 引用的 bean 名（InfoSourceCatalog EM_MACRO_INDICATORS）。 */
    public static final String BEAN_NAME = "eastmoneyMacroAdapter";

    /** 每源连接/读取超时（方案 §5：单源超时 5s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger log = LoggerFactory.getLogger(EastmoneyMacroAdapter.class);

    /** REPORT_DATE 墙钟格式（源侧北京时间，数据期归一月首日 00:00:00）。 */
    private static final DateTimeFormatter REPORT_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每报表单轮取数行数（月度序列 20 期 ≈ 1.7 年窗口，断流补抓由去重幂等吸收，无需深翻）。 */
    private static final int PAGE_ROWS = 20;

    /** datacenter 基端点（reportName/pageSize/sort 等参数由 {@link #reportUrl} 拼装）。 */
    private static final String BASE_ENDPOINT =
            "https://datacenter-web.eastmoney.com/api/data/v1/get";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120 Safari/537.36";

    /** 宏观报表字典：reportName（端点参数）+ 指标名（条目标题用）。首批 CPI（2026-09-25 实测）。 */
    record MacroReport(String reportName, String indicatorName) {}

    private static final List<MacroReport> REPORTS =
            List.of(new MacroReport("RPT_ECONOMY_CPI", "CPI"));

    private final RestClient restClient;

    /** 生产装配（5s 超时 + 浏览器 UA，礼貌抓取）。 */
    @Autowired
    public EastmoneyMacroAdapter(RestClient.Builder builder) {
        this(builder.requestFactory(requestFactory()).build());
    }

    /** 全参构造（单测注入受控 RestClient）。 */
    EastmoneyMacroAdapter(RestClient restClient) {
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
        List<RawFeedItem> items = new ArrayList<>();
        int failedReports = 0;
        for (MacroReport report : REPORTS) {
            try {
                items.addAll(parseReport(httpGet(report), source, report));
            } catch (FeedFetchException e) {
                // 单报表源侧失败（success=false/结构漂移）：记上下文跳过续跑；全字典失败才置轮失败（走退避）
                failedReports++;
                log.warn(
                        "宏观报表取数跳过 source={} report={} 原因={}",
                        source.getSourceCode(),
                        report.reportName(),
                        e.getMessage());
            }
        }
        if (failedReports == REPORTS.size()) {
            throw new FeedFetchException(
                    "东财宏观全部报表取数失败 " + source.getSourceCode() + "（报表数 " + REPORTS.size() + "）");
        }
        return new FetchResult(List.copyOf(items), false);
    }

    private String httpGet(MacroReport report) {
        String url = reportUrl(report);
        try {
            String body =
                    restClient
                            .get()
                            .uri(url)
                            .header("User-Agent", USER_AGENT)
                            .retrieve()
                            .body(String.class);
            if (body == null || body.isBlank()) {
                throw new FeedFetchException("东财宏观空响应 " + report.reportName() + " " + url);
            }
            return body;
        } catch (RestClientException e) {
            throw new FeedFetchException(
                    "东财宏观取数失败 " + report.reportName() + " " + url + ": " + e.getMessage(), e);
        }
    }

    /** 报表取数 URL（显式 REPORT_DATE 降序——默认序最旧在前，实测口径见类注释）。 */
    static String reportUrl(MacroReport report) {
        return BASE_ENDPOINT
                + "?reportName="
                + report.reportName()
                + "&columns=ALL&pageSize="
                + PAGE_ROWS
                + "&pageNumber=1&sortColumns=REPORT_DATE&sortTypes=-1";
    }

    /** 解析单报表（包内可见，fixture 单测直调零外呼）：result.data 行数组 → 条目化。 */
    List<RawFeedItem> parseReport(String body, InfoSource source, MacroReport report) {
        JsonNode rows;
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                throw new FeedFetchException(
                        "东财宏观源侧报错 "
                                + report.reportName()
                                + ": code="
                                + root.path("code").asText()
                                + " message="
                                + root.path("message").asText());
            }
            rows = root.path("result").path("data");
        } catch (FeedFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FeedFetchException(
                    "东财宏观 JSON 解析失败 " + report.reportName() + ": " + e.getMessage(), e);
        }
        if (!rows.isArray()) {
            throw new FeedFetchException("东财宏观结构漂移（result.data 缺失）: " + report.reportName());
        }
        List<RawFeedItem> items = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            RawFeedItem item = toItem(row, source, report);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 序列行 → 条目（映射裁量见类注释；REPORT_DATE/TIME/同比值缺失的行跳过——无期次不成条目）。 */
    private static RawFeedItem toItem(JsonNode row, InfoSource source, MacroReport report) {
        String rawReportDate = row.path("REPORT_DATE").asText(null);
        String periodLabel = row.path("TIME").asText(null);
        Instant publishedAt = parseReportDate(rawReportDate);
        if (periodLabel == null || periodLabel.isBlank() || publishedAt == null) {
            return null;
        }
        String same = decimalText(row, "NATIONAL_SAME");
        if (same == null) {
            return null;
        }
        // externalId 期次段取源侧 REPORT_DATE 日期（墙钟即数据期本地日，与 TIME 中文期次一致）
        String externalId = report.reportName() + "#" + rawReportDate.substring(0, 10);
        String title = report.indicatorName() + "：" + periodLabel + " 同比 " + same + "%";
        String summary = summaryOf(row);
        return new RawFeedItem(
                externalId,
                title,
                summary,
                null,
                "东方财富数据中心",
                publishedAt,
                source.getConfig().effectiveCursorType() == CursorType.TIME
                        ? publishedAt.toString()
                        : null);
    }

    /** 摘要段（列缺失逐段降级，不硬拼 null；全缺返回 null）。 */
    private static String summaryOf(JsonNode row) {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "全国同比 ", decimalText(row, "NATIONAL_SAME"), "%");
        appendSection(sb, "，环比 ", decimalText(row, "NATIONAL_SEQUENTIAL"), "%");
        appendSection(sb, "，累计 ", decimalText(row, "NATIONAL_ACCUMULATE"));
        appendSection(sb, "；城市同比 ", decimalText(row, "CITY_SAME"), "%");
        appendSection(sb, "，农村同比 ", decimalText(row, "RURAL_SAME"), "%");
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendSection(
            StringBuilder sb, String prefix, String value, String suffix) {
        if (value != null) {
            sb.append(prefix).append(value).append(suffix);
        }
    }

    private static void appendSection(StringBuilder sb, String prefix, String value) {
        if (value != null) {
            sb.append(prefix).append(value);
        }
    }

    /** 数值列文本化（去尾零：2 → "2"、1.0103 → "1.0103"；缺失/非数值返回 null）。 */
    private static String decimalText(JsonNode row, String column) {
        JsonNode node = row.get(column);
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.decimalValue().stripTrailingZeros().toPlainString();
    }

    /** REPORT_DATE 墙钟（Asia/Shanghai）→ Instant；缺失/不可解析返回 null。 */
    private static Instant parseReportDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, REPORT_DATE)
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
