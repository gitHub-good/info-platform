package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 新浪财经 vFD 财务 HTTP 客户端（{@code money.finance.sina.com.cn/corp/go.php/vFD_*}，ADR-0034 T55
 * 财务备选源）：两页组合取「最小完备集」，由 {@link FinanceSourceAdapter} 在东财失败/空响应（9501 事故签名）降级时调用。
 *
 * <p><b>两页组合</b>（单页均不合格，ADR-0034 被否清单）：利润表 {@code vFD_ProfitStatement}（报表日期/营收/归母净利，万元）+ 财务指标页
 * {@code vFD_FinancialGuideLine}（加权 ROE/净利率/毛利率）——分别缺 ROE / 缺营收绝对值，组合是新浪体系唯一最小完备集拼法。
 *
 * <p><b>输出即东财中间结构</b>（ADR-0031 模式）：本客户端把两页解析结果映射为<b>东财 F10 键</b>（SECURITY_CODE / TOTALOPERATEREVE /
 * PARENTNETPROFIT / ROEJQ / XSJLL / XSMLL / REPORT_DATE）， {@code
 * field-mapping/eastmoney-finance.json} 零改动即可消费——备选源对聚合契约完全透明。
 *
 * <h2>响应契约（2026-09-24 实测，ADR-0034 附录 B）</h2>
 *
 * <ul>
 *   <li>GBK HTML（content-type {@code text/html; charset=gbk}）——取 {@code byte[]} 显式解码 + Jsoup（对齐
 *       {@link TencentQuoteClient}/{@link GovPolicyClient} 先例）
 *   <li>表格按 id 定位：利润表 {@code ProfitStatementNewTable0} / 指标页 {@code BalanceSheetNewTable0}（id
 *       跨页复用为上游历史命名）；行 = {@code tr}、首格为行名、其余格按列为报告期值
 *   <li>「报表日期/报告日期」行为列头，<b>第 1 数据列 = 最新报告期</b>（两页均倒序列）；金额<b>万元</b>含千分位逗号； 缺失值 '--'（金融股净利率/毛利率大面积缺失）
 *   <li>限速友好：无 WAF、空 UA 亦 200、8 连发全 200——仍带浏览器 UA + Referer（沿 {@link SinaNewsClient} 同款防御）
 *   <li>vFD 系为 A 股页面，港股不可查——对齐主源现状（东财 F10 港股实测 {@code code:9201} 空体，ADR-0034 §1）
 * </ul>
 *
 * <h2>行名变体核对表（2026-09-24 实测 + 与东财同报告期交叉核对零偏差，附录 A）</h2>
 *
 * <table border="1">
 *   <caption>取值 → 行名变体（任一匹配即取；标签先归一：去空白、全角括号转半角）</caption>
 *   <tr><th>取值</th><th>行名变体</th><th>→ 东财键（转换）</th></tr>
 *   <tr><td>营收</td><td>一、营业总收入（通用）/ 一、营业收入（银行/保险/券商）</td>
 *       <td>TOTALOPERATEREVE（万元 ×10000 → 元）</td></tr>
 *   <tr><td>归母净利</td><td>正则 {@code ^归属于母公司(所有者|股东)?的净利润$}（三种实测变体）</td>
 *       <td>PARENTNETPROFIT（万元 ×10000 → 元）</td></tr>
 *   <tr><td>ROE</td><td>加权净资产收益率(%)——<b>禁用</b>「净资产收益率(%)」摊薄口径（茅台 17.72 vs 加权 16.75）</td>
 *       <td>ROEJQ</td></tr>
 *   <tr><td>净利率 / 毛利率</td><td>销售净利率(%) / 销售毛利率(%)</td><td>XSJLL / XSMLL</td></tr>
 *   <tr><td>报表日期</td><td>yyyy-MM-dd</td><td>REPORT_DATE（补 {@code " 00:00:00"} 对齐东财格式）</td></tr>
 * </table>
 *
 * <p><b>处理链</b>：年份回退（当年利润表无报告期行 → year-1 重拉一次，年初年报未披露窗口；仍无 → empty）→ 两页报告期一致性防御（以利润表报告期为准，
 * 指标页按「列头日期 == 利润表报告期」对位取列，找不到 → 比率键不产出、营收/净利/报告期仍产出）→ 缺失值防御（'--'/空/短行 → 该键不产出， 白名单语义）→ 不可解析数值 WARN
 * 跳过。
 *
 * <p><b>两页组合非原子</b>：任一页 HTTP 失败抛异常（该级失败交链执行器，整轮放弃不产出半套——最小完备集完整性优先，方案 §7 取舍）。
 * 表格缺席/无报告期行（页面改版或当年未披露）返回 empty（→ 链下一级 / MISSING）。
 *
 * <p>超时不在此设——弹性预算两轮合用（FINANCE noRetry 2s 覆盖东财一轮 + 新浪两页，ADR-0034 §3）； URL 模板运行时可调（种子入 {@link
 * DataSourceDefaults}，页面可改，ADR-0032）。
 */
@Component
public class SinaFinanceClient {

    private static final Logger log = LoggerFactory.getLogger(SinaFinanceClient.class);

    /** 利润表页 URL 模板（缺省取 {@link DataSourceDefaults}，构造期回落）。 */
    static final String DEFAULT_PROFIT_URL_TEMPLATE =
            DataSourceDefaults.paramString(SourceCode.FINANCE, "financeSinaProfitUrlTemplate");

    /** 财务指标页 URL 模板（同上）。 */
    static final String DEFAULT_GUIDE_URL_TEMPLATE =
            DataSourceDefaults.paramString(SourceCode.FINANCE, "financeSinaGuideUrlTemplate");

    /** 响应体编码（money.finance.sina.com.cn 实测 text/html; charset=gbk）。 */
    private static final Charset RESPONSE_CHARSET = Charset.forName("GBK");

    /** 浏览器 UA（防御性携带，沿 SinaNewsClient 同款——vFD 实测空 UA 也可通）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** 新浪软限频来源页（沿 SinaNewsClient 同款；vFD 实测无 Referer 要求，防御性携带）。 */
    private static final String REFERER = "https://finance.sina.com.cn";

    /** 利润表表格 id（2026-09-24 实测）。 */
    static final String PROFIT_TABLE_ID = "ProfitStatementNewTable0";

    /** 财务指标页表格 id（2026-09-24 实测；id 命名与页面语义错位为上游历史命名）。 */
    static final String GUIDE_TABLE_ID = "BalanceSheetNewTable0";

    /** 列头行名（利润表「报表日期」/ 指标页「报告日期」两种实测写法）。 */
    private static final Pattern REPORT_DATE_ROW_PATTERN = Pattern.compile("^报[表告]日期$");

    /** 营收行名变体（通用 / 金融业）。 */
    private static final String REVENUE_ROW_TOTAL = "一、营业总收入";

    private static final String REVENUE_ROW_OPERATING = "一、营业收入";

    /** 归母净利行名变体（所有者 / 无 / 股东，三种实测）。 */
    private static final Pattern PARENT_NET_PROFIT_PATTERN =
            Pattern.compile("^归属于母公司(所有者|股东)?的净利润$");

    /** 加权 ROE 行名（与东财 ROEJQ 同「加权」口径；摊薄行「净资产收益率(%)」禁用，见核对表）。 */
    private static final String ROE_ROW = "加权净资产收益率(%)";

    private static final String NET_MARGIN_ROW = "销售净利率(%)";
    private static final String GROSS_MARGIN_ROW = "销售毛利率(%)";

    /** 万元 → 元换算因子（对齐东财 TOTALOPERATEREVE/PARENTNETPROFIT 元单位）。 */
    private static final BigDecimal WAN_TO_YUAN = BigDecimal.valueOf(10_000);

    /** REPORT_DATE 补零时段（对齐东财 {@code yyyy-MM-dd HH:mm:ss} 格式，to_iso_date 映射零改动）。 */
    private static final String REPORT_DATE_TIME_SUFFIX = " 00:00:00";

    /** 最新报告期所在数据列（两页均倒序列，第 1 数据列即最新）。 */
    private static final int LATEST_REPORT_COLUMN = 0;

    private final RestClient restClient;
    private final String profitUrlTemplate;
    private final String guideUrlTemplate;

    /** 配置中心（T36 热化）：null（纯构造单测）时回落构造期缺省。 */
    @Autowired(required = false)
    ConfigCenter configCenter;

    /** Spring 装配构造（ADR-0032）：回落值取 {@link DataSourceDefaults} 代码内置缺省。 */
    @Autowired
    public SinaFinanceClient(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_PROFIT_URL_TEMPLATE, DEFAULT_GUIDE_URL_TEMPLATE);
    }

    /** 全参构造（纯构造单测指定回落值）。 */
    public SinaFinanceClient(
            RestClient.Builder restClientBuilder,
            String profitUrlTemplate,
            String guideUrlTemplate) {
        this.restClient = restClientBuilder.build();
        this.profitUrlTemplate = profitUrlTemplate;
        this.guideUrlTemplate = guideUrlTemplate;
    }

    /**
     * 取某 6 位证券代码最新报告期一条主财务指标（东财 F10 键中间结构）。
     *
     * @param securityCode 6 位证券代码，如 {@code 600519}（沪）/ {@code 000001}（深）
     * @return 东财键 map；利润表当年/前年均无报告期行时 {@link Optional#empty()}（→ 链下一级 / MISSING）
     * @throws org.springframework.web.client.RestClientException 两页任一 HTTP 失败（该级失败，整轮放弃不产出半套）
     */
    public Optional<Map<String, Object>> fetchFinance(String securityCode) {
        String profitTemplate =
                RuntimeParams.of(
                        configCenter,
                        SourceCode.FINANCE,
                        "financeSinaProfitUrlTemplate",
                        this.profitUrlTemplate);
        String guideTemplate =
                RuntimeParams.of(
                        configCenter,
                        SourceCode.FINANCE,
                        "financeSinaGuideUrlTemplate",
                        this.guideUrlTemplate);
        int currentYear = Year.now().getValue();
        for (int year = currentYear; year > currentYear - 2; year--) {
            String profitHtml = fetchPage(buildUrl(profitTemplate, securityCode, year));
            SinaTable profit = SinaTable.parse(profitHtml, PROFIT_TABLE_ID);
            if (profit == null || profit.firstReportDate() == null) {
                log.info("新浪利润表无报告期行，年份回退 securityCode={} year={}", securityCode, year);
                continue;
            }
            String guideHtml = fetchPage(buildUrl(guideTemplate, securityCode, year));
            return parseFinance(profitHtml, guideHtml, securityCode);
        }
        return Optional.empty();
    }

    /**
     * 两页 HTML → 东财键中间结构（纯解析静态缝，供单测直接喂数据不依赖网络，同 {@link GovPolicyClient#parsePolicies} 可测性策略）。
     *
     * @param profitHtml 利润表页 HTML（GBK 已解码）
     * @param guideHtml 财务指标页 HTML（GBK 已解码）；null / 无表格 → 比率键不产出
     * @param securityCode 6 位证券代码（原样进 {@code SECURITY_CODE}）
     * @return 东财键 map；利润表无报告期行返回 {@link Optional#empty()}
     */
    static Optional<Map<String, Object>> parseFinance(
            String profitHtml, String guideHtml, String securityCode) {
        SinaTable profit = SinaTable.parse(profitHtml, PROFIT_TABLE_ID);
        if (profit == null || profit.firstReportDate() == null) {
            return Optional.empty();
        }
        String reportDate = profit.firstReportDate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("SECURITY_CODE", securityCode);
        row.put("REPORT_DATE", reportDate + REPORT_DATE_TIME_SUFFIX);
        putWanToYuan(
                row,
                "TOTALOPERATEREVE",
                profit.valueAt(LATEST_REPORT_COLUMN, REVENUE_ROW_TOTAL, REVENUE_ROW_OPERATING));
        putWanToYuan(
                row,
                "PARENTNETPROFIT",
                profit.valueAt(LATEST_REPORT_COLUMN, PARENT_NET_PROFIT_PATTERN));
        putRatios(row, SinaTable.parse(guideHtml, GUIDE_TABLE_ID), reportDate, securityCode);
        return Optional.of(row);
    }

    /** 指标页比率键：按「列头日期 == 利润表报告期」对位取列（披露时点差防御），找不到列/行缺失 → 不产出。 */
    private static void putRatios(
            Map<String, Object> row, SinaTable guide, String reportDate, String securityCode) {
        if (guide == null || guide.firstReportDate() == null) {
            return;
        }
        int column = guide.columnOf(reportDate);
        if (column < 0) {
            log.warn(
                    "新浪两页报告期错位，比率指标不产出 securityCode={} profitDate={} guideDates={}",
                    securityCode,
                    reportDate,
                    guide.reportDates());
            return;
        }
        putDecimal(row, "ROEJQ", guide.valueAt(column, ROE_ROW));
        putDecimal(row, "XSJLL", guide.valueAt(column, NET_MARGIN_ROW));
        putDecimal(row, "XSMLL", guide.valueAt(column, GROSS_MARGIN_ROW));
    }

    /** 单页拉取：读 byte[] 显式 GBK 解码（不依赖转换器对 charset 声明的行为）。 */
    private String fetchPage(String url) {
        log.debug("新浪财务请求 url={}", url);
        byte[] body =
                restClient
                        .get()
                        .uri(URI.create(url))
                        .accept(MediaType.TEXT_HTML)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .retrieve()
                        .body(byte[].class);
        return body == null ? "" : new String(body, RESPONSE_CHARSET);
    }

    /** URL 模板占位符替换（securityCode 为 6 位数字、year 为 4 位年份，无编码风险）。 */
    private static String buildUrl(String template, String securityCode, int year) {
        return template.replace("{code}", securityCode).replace("{year}", String.valueOf(year));
    }

    /** 金额：万元 ×10000 → 元（对齐东财元单位）；缺失/不可解析 → 该键不产出。 */
    private static void putWanToYuan(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value.multiply(WAN_TO_YUAN)));
    }

    /** 比率：原值直传（百分比口径与东财一致，附录 A 零偏差）；缺失/不可解析 → 该键不产出。 */
    private static void putDecimal(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value));
    }

    /** 数值解析：空白与 '--'（新浪缺失约定，'-' 同防）视为字段缺失返回 empty；剔千分位逗号后不可解析记 WARN 跳过。 */
    private static Optional<BigDecimal> parseDecimal(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.replace(",", "").replace("，", "");
        if (cleaned.isEmpty() || "--".equals(cleaned) || "-".equals(cleaned)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(cleaned));
        } catch (NumberFormatException e) {
            log.warn("新浪财务数值不可解析，跳过 value='{}'", raw);
            return Optional.empty();
        }
    }

    /**
     * 新浪 vFD 表格最小结构模型：列头报告期（倒序）+ 行名 → 各列单元格文本（均已归一）。
     *
     * <p>归一规则：去全部空白（含不间断空格/全角空格——行名/日期/数值内均无有意义空白）、全角括号转半角（行名半/全角括号两种排版防御）。
     */
    private static final class SinaTable {

        private final List<String> reportDates = new ArrayList<>();
        private final Map<String, List<String>> rowsByLabel = new LinkedHashMap<>();

        /** 解析 HTML 中指定 id 的表格；HTML 空/表格缺席返回 null（页面改版防御，调用方按无数据处理）。 */
        static SinaTable parse(String html, String tableId) {
            if (html == null || html.isBlank()) {
                return null;
            }
            Element table = Jsoup.parse(html).getElementById(tableId);
            if (table == null) {
                return null;
            }
            SinaTable parsed = new SinaTable();
            for (Element tr : table.select("tr")) {
                Elements cells = tr.select("td, th");
                if (cells.isEmpty()) {
                    continue;
                }
                String label = normalize(cells.first().text());
                if (label.isEmpty()) {
                    continue;
                }
                List<String> values = new ArrayList<>(cells.size() - 1);
                for (int i = 1; i < cells.size(); i++) {
                    values.add(normalize(cells.get(i).text()));
                }
                if (REPORT_DATE_ROW_PATTERN.matcher(label).matches()) {
                    parsed.reportDates.addAll(values);
                } else {
                    parsed.rowsByLabel.putIfAbsent(label, values);
                }
            }
            return parsed;
        }

        /** 最新报告期（第 1 数据列列头）；无数据列返回 null。 */
        String firstReportDate() {
            return reportDates.isEmpty() ? null : reportDates.get(0);
        }

        /** 报告期 → 列索引（对位取列用）；不存在返回 -1。 */
        int columnOf(String reportDate) {
            return reportDates.indexOf(reportDate);
        }

        /** 列头清单（日志留痕用）。 */
        List<String> reportDates() {
            return List.copyOf(reportDates);
        }

        /** 精确行名（多个变体任一命中）→ 指定列单元格文本；行/列缺席返回 null。 */
        String valueAt(int column, String... rowLabels) {
            for (String label : rowLabels) {
                List<String> values = rowsByLabel.get(label);
                if (values != null && column < values.size()) {
                    return values.get(column);
                }
            }
            return null;
        }

        /** 正则行名（首个命中）→ 指定列单元格文本；行/列缺席返回 null。 */
        String valueAt(int column, Pattern rowPattern) {
            for (Map.Entry<String, List<String>> entry : rowsByLabel.entrySet()) {
                if (rowPattern.matcher(entry.getKey()).matches()
                        && column < entry.getValue().size()) {
                    return entry.getValue().get(column);
                }
            }
            return null;
        }

        /** 文本归一：去全部空白（含 nbsp/全角空格）+ 全角括号转半角。 */
        private static String normalize(String raw) {
            if (raw == null) {
                return "";
            }
            return raw.replace("（", "(")
                    .replace("）", ")")
                    .replace("\u00A0", "")
                    .replace("　", "")
                    .replaceAll("\\s+", "");
        }
    }
}
