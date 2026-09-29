package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SubjectCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 新浪行情 HTTP 客户端（{@code hq.sinajs.cn/list=},M29 T252 备链，ADR-0064 裁决 1）：港美股行情备通道，产出与 {@link
 * TencentQuoteClient} 同款<b>东财 f 键中间结构</b>——快照层经 {@code HkusQuoteSource} 双源轮级互切对适配透明。
 *
 * <h2>符号空间（Spike-E E-2b 实测）</h2>
 *
 * <ul>
 *   <li>港股：<b>必须 {@code rt_hk} 实时前缀</b>（{@code hk_} 裸前缀延迟 15 分钟，不用）——{@code HK00700}→{@code
 *       rt_hk00700}
 *   <li>美股：{@code gb_} 小写 ticker——{@code USAAPL}→{@code gb_aapl}（与腾讯 US 大写符号相反）
 * </ul>
 *
 * <h2>字段核对表（2026-09-29 curl 实测 + 腾讯同刻同值交叉验证，索引 = 逗号分隔位）</h2>
 *
 * <table border="1">
 *   <caption>rt_hk 港股实时行（19+ 位，标签位无标签名）</caption>
 *   <tr><th>索引</th><th>含义</th><th>→ f 键</th><th>验证</th></tr>
 *   <tr><td>1 / 0</td><td>中文名 / 英文名</td><td>f58</td><td>腾讯控股</td></tr>
 *   <tr><td>6 / 3 / 2</td><td>现价 / 昨收 / 今开（HKD）</td><td>f43 / f60 / f46</td>
 *       <td>432.000 / 439.800 / 439.400 与腾讯同刻同值</td></tr>
 *   <tr><td>4 / 5</td><td>最高 / 最低</td><td>f44 / f45</td><td>439.400 / 431.600</td></tr>
 *   <tr><td>7 / 8</td><td>涨跌 / 涨跌幅 %</td><td>f169 / f170</td><td>-7.800 / -1.774</td></tr>
 *   <tr><td>12 / 11</td><td>成交量（股）/ 成交额（HKD，已是元）</td><td>f47 / f48</td>
 *       <td>18015236 / 7808702026.492 三字段交叉一致</td></tr>
 *   <tr><td>13</td><td>PE（口径与腾讯可能不同，以源标注不对账）</td><td>f162</td><td>15.701</td></tr>
 *   <tr><td>17 + 18</td><td>源日期 + 时间（拼接 {@code yyyy/MM/dd HH:mm:ss}）</td><td>f30</td><td>2026/09/29 16:08:08</td></tr>
 * </table>
 *
 * <table border="1">
 *   <caption>gb_ 美股行（30+ 位）</caption>
 *   <tr><th>索引</th><th>含义</th><th>→ f 键</th><th>验证</th></tr>
 *   <tr><td>0</td><td>中文名</td><td>f58</td><td>苹果</td></tr>
 *   <tr><td>1 / 4 / 2</td><td>现价 / 涨跌 / 涨跌幅 %（USD）</td><td>f43 / f169 / f170</td>
 *       <td>338.4000 / -2.6700 / -0.78</td></tr>
 *   <tr><td>5 / 6 / 7</td><td>今开 / 最高 / 最低</td><td>f46 / f44 / f45</td><td>340.37 / 342.99 / 338.04</td></tr>
 *   <tr><td>10</td><td>成交量（股）</td><td>f47</td><td>32820844</td></tr>
 *   <tr><td>12</td><td>总市值（<b>USD 原值</b>，÷1e8 归一亿 USD 对齐腾讯 @44 单位）</td><td>market_cap</td>
 *       <td>4938671029752 → 49386.71 亿（腾讯 49356.00844 交叉一致）</td></tr>
 *   <tr><td>14</td><td>PE（TTM/静态口径与腾讯不一致，Spike-E 留档不对账）</td><td>f162</td><td>40.77</td></tr>
 *   <tr><td>3</td><td>行情时间（北京时间墙钟）</td><td>f30</td><td>2026-09-29 19:38:15</td></tr>
 * </table>
 *
 * <p>gb_ 行<b>无昨收位</b>（f60 不产出，白名单语义）；盘前盘后位（@21~25）腾讯无对应不映射（交集消费，Spike-E §2.2）。
 * 币种新浪行内无字段——由符号族派生（rt_hk→HKD / gb_→USD，见 {@code HkusQuoteSource} 适配层）。
 *
 * <h2>响应契约（2026-09-29 实测）</h2>
 *
 * <ul>
 *   <li>编码 GBK（{@code text/html; charset=GBK}）——本客户端取 {@code byte[]} 显式解码
 *   <li>行格式 {@code var hq_str_rt_hk00700="..,..";}；批量逗号拼接；无效代码行缺席（无错误行）
 *   <li><b>必带 {@code Referer: https://finance.sina.com.cn}</b>（缺 Referer 403，SinaNewsClient 同款）
 * </ul>
 *
 * <p>防御：垃圾行 / 字段数不足（HK &lt;19 / US &lt;15）整行跳过记 WARN；数值空白 "-" 缺失语义同腾讯。超时不在此设置——
 * 弹性超时由调用方（快照轮）统一兜底；HTTP 异常直接抛出由轮级降级语义处理（整轮切回/降级）。
 */
@Component
public class SinaQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(SinaQuoteClient.class);

    static final String DEFAULT_QUOTE_URL = "https://hq.sinajs.cn/list=";

    /** 响应体编码（hq.sinajs.cn 实测 GBK）。 */
    private static final Charset RESPONSE_CHARSET = Charset.forName("GBK");

    /** 新浪行情 Referer（实测缺 Referer 403，DataSourceDefaults.SINA_STOCK_REFERER 同值）。 */
    static final String REFERER = "https://finance.sina.com.cn";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** 单请求标的数上限防御（沿腾讯 50/请求惯例；超出分块多次请求）。 */
    static final int MAX_SYMBOLS_PER_REQUEST = 50;

    /** rt_hk 合法行最小字段数（实测 25 位；防御短行）。 */
    private static final int MIN_HK_FIELDS = 19;

    /** gb_ 合法行最小字段数（实测 36 位；PE@14 之后位不依赖，15 起防御）。 */
    private static final int MIN_US_FIELDS = 15;

    /** 新浪美股市值 USD 原值 → 亿 USD 的换算因子（对齐腾讯 @44 单位）。 */
    private static final BigDecimal USD_TO_YI = BigDecimal.valueOf(100_000_000);

    private final RestClient restClient;

    private final String quoteUrl;

    /** Spring 装配构造（ADR-0032：端点缺省代码内置值）。 */
    @Autowired
    public SinaQuoteClient(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DEFAULT_QUOTE_URL);
    }

    /** 全参构造（纯构造单测指定端点）。 */
    public SinaQuoteClient(RestClient.Builder restClientBuilder, String quoteUrl) {
        this.restClient = restClientBuilder.build();
        this.quoteUrl = quoteUrl;
    }

    /**
     * 批量取数（单请求 ≤{@value MAX_SYMBOLS_PER_REQUEST} 只自动分块）。
     *
     * @param sinaSymbols 新浪符号列表（{@code rt_hk00700} / {@code gb_aapl}）
     * @return 键 = 响应行自带符号 → f 键中间结构；无效符号行缺席即无键（不视为错误）
     */
    public Map<String, Map<String, Object>> fetchQuotes(java.util.List<String> sinaSymbols) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        if (sinaSymbols == null || sinaSymbols.isEmpty()) {
            return rows;
        }
        for (int i = 0; i < sinaSymbols.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            java.util.List<String> chunk =
                    sinaSymbols.subList(
                            i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, sinaSymbols.size()));
            rows.putAll(fetchChunk(chunk));
        }
        return rows;
    }

    /**
     * 内部标的码 → 新浪符号：{@code HK00700}→{@code rt_hk00700}（实时前缀，裸 {@code hk_} 延迟 15 分钟不用）；{@code
     * USAAPL}→{@code gb_aapl}（ticker 小写——与腾讯 US 大写符号相反）。
     *
     * @return 前缀不在 HK/US 或代码段为空时 {@link Optional#empty()}
     */
    public static Optional<String> toSinaSymbol(SubjectCode subjectCode) {
        String value = subjectCode.value().trim();
        if (value.length() <= 2) {
            return Optional.empty();
        }
        String prefix = value.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
        String code = value.substring(2);
        if (code.isBlank()) {
            return Optional.empty();
        }
        return switch (prefix) {
            case "HK" -> Optional.of("rt_hk" + code);
            case "US" -> Optional.of("gb_" + code.toLowerCase(java.util.Locale.ROOT));
            default -> Optional.empty();
        };
    }

    // ---- 内部实现 ----

    private Map<String, Map<String, Object>> fetchChunk(java.util.List<String> chunk) {
        String url = quoteUrl + String.join(",", chunk);
        log.debug("新浪行情备链请求 symbols={}", chunk.size());
        byte[] body =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.TEXT_HTML)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", REFERER)
                        .retrieve()
                        .body(byte[].class);
        return parseBody(body == null ? "" : new String(body, RESPONSE_CHARSET));
    }

    /** 解析响应体：逐行解析，垃圾/短行跳过记 WARN，键 = 行自带符号。 */
    private Map<String, Map<String, Object>> parseBody(String body) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            parseLine(line).ifPresent(parsed -> rows.put(parsed.symbol(), parsed.fields()));
        }
        return rows;
    }

    /** 单行解析：{@code var hq_str_rt_hk00700="..";}. */
    private Optional<ParsedRow> parseLine(String line) {
        int assignment = line.indexOf("=\"");
        String prefix = "var hq_str_";
        if (!line.startsWith(prefix) || assignment < prefix.length() || !line.endsWith(";")) {
            log.warn("新浪行情行格式异常，跳过 line={}", abbreviate(line));
            return Optional.empty();
        }
        String symbol = line.substring(prefix.length(), assignment);
        String payload = line.substring(assignment + 2, line.length() - 2);
        String[] fields = payload.split(",", -1);
        boolean hk = symbol.startsWith("rt_hk");
        int minFields = hk ? MIN_HK_FIELDS : MIN_US_FIELDS;
        if (!hk && !symbol.startsWith("gb_")) {
            log.warn("新浪行情行符号族不支持，跳过 symbol={}", symbol);
            return Optional.empty();
        }
        if (fields.length < minFields) {
            log.warn("新浪行情行字段数不足，跳过 symbol={} fields={}", symbol, fields.length);
            return Optional.empty();
        }
        return Optional.of(new ParsedRow(symbol, hk ? mapHkFields(fields) : mapUsFields(fields)));
    }

    /** rt_hk 港股实时行 → f 键中间结构（核对表见类 Javadoc）。 */
    private Map<String, Object> mapHkFields(String[] f) {
        Map<String, Object> row = new LinkedHashMap<>();
        putText(row, "f58", f[1]);
        putDecimal(row, "f43", f[6]);
        putDecimal(row, "f60", f[3]);
        putDecimal(row, "f46", f[2]);
        putDecimal(row, "f44", f[4]);
        putDecimal(row, "f45", f[5]);
        putDecimal(row, "f169", f[7]);
        putDecimal(row, "f170", f[8]);
        putLong(row, "f47", f[12]);
        putDecimal(row, "f48", f[11]);
        putDecimal(row, "f162", f[13]);
        if (hasValue(f[17]) && hasValue(f[18])) {
            row.put("f30", f[17] + " " + f[18]);
        }
        return row;
    }

    /** gb_ 美股行 → f 键中间结构（无昨收位；市值 USD 原值归一亿）。 */
    private Map<String, Object> mapUsFields(String[] f) {
        Map<String, Object> row = new LinkedHashMap<>();
        putText(row, "f58", f[0]);
        putDecimal(row, "f43", f[1]);
        putDecimal(row, "f170", f[2]);
        putText(row, "f30", f[3]);
        putDecimal(row, "f169", f[4]);
        putDecimal(row, "f46", f[5]);
        putDecimal(row, "f44", f[6]);
        putDecimal(row, "f45", f[7]);
        putLong(row, "f47", f[10]);
        Optional<BigDecimal> marketCap = parseDecimal(f[12]);
        marketCap.ifPresent(
                value ->
                        row.put(
                                TencentQuoteClient.MARKET_CAP_KEY,
                                value.divide(USD_TO_YI, 4, RoundingMode.HALF_UP)));
        putDecimal(row, "f162", f[14]);
        return row;
    }

    private void putText(Map<String, Object> row, String key, String raw) {
        if (hasValue(raw)) {
            row.put(key, raw.trim());
        }
    }

    private void putDecimal(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value));
    }

    /** 成交量归一为 long（对齐腾讯 f47 数值口径）。 */
    private void putLong(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value.longValue()));
    }

    /** 数值解析：空白与 "-" 缺失约定同腾讯；不可解析记 WARN 跳过该字段（半行优于整行丢弃）。 */
    private Optional<BigDecimal> parseDecimal(String raw) {
        if (!hasValue(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("新浪行情字段数值不可解析，跳过 value='{}'", raw);
            return Optional.empty();
        }
    }

    private static boolean hasValue(String raw) {
        return raw != null && !raw.isBlank() && !"-".equals(raw.trim());
    }

    private static String abbreviate(String line) {
        return line.length() <= 60 ? line : line.substring(0, 60) + "...";
    }

    /** 解析出的单行：行自带符号 + f 键映射。 */
    private record ParsedRow(String symbol, Map<String, Object> fields) {}
}
