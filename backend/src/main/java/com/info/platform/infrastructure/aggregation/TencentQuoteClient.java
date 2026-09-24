package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 腾讯行情 HTTP 客户端（{@code qt.gtimg.cn/q=}，ADR-0031 行情/估值备选源）：行情与估值共用一个端点 一次返回全部字段（区别于东财按 f 字段列表裁剪），由
 * {@link QuoteSourceAdapter} / {@link ValuationSourceAdapter} 在 auto 降级 / 强制 tencent 时调用。
 *
 * <p><b>输出即东财中间结构</b>：本客户端把 ~ 分隔字段映射为<b>东财 f 键</b>（f43 现价 / f60 昨收 / f162 估值……）， 两 adapter
 * 的既有字段映射配置（{@code eastmoney-quote.json} / {@code eastmoney-valuation.json}）零改动即可消费——
 * 备选源对聚合契约完全透明（ADR-0031 裁定：client 层路由 + 中间结构对齐，不改 SourceAdapter 端口）。
 *
 * <h2>字段核对表（2026-09-24 curl 实测 + 东财 push2 同日交叉验证，ADR-0031）</h2>
 *
 * <table border="1">
 *   <caption>~ 分隔索引 → 含义 → 映射 f 键（A 股 / 港股分列者为两市场字段位不同）</caption>
 *   <tr><th>索引</th><th>A 股含义</th><th>→ f 键</th><th>验证（东财同值对照）</th></tr>
 *   <tr><td>1</td><td>名称</td><td>f58</td><td>—</td></tr>
 *   <tr><td>2</td><td>代码</td><td>f57</td><td>—</td></tr>
 *   <tr><td>3</td><td>现价</td><td>f43</td><td>茅台 1237.00 = f43 1237.0；腾讯控股 438.400 = f43 438.4</td></tr>
 *   <tr><td>4</td><td>昨收</td><td>f60</td><td>1251.24 = f60 1251.24</td></tr>
 *   <tr><td>5</td><td>今开</td><td>f46</td><td>1250.01 = f46 1250.01</td></tr>
 *   <tr><td>6</td><td>成交量（A 股手 / 港股股）</td><td>f47</td><td>31239 手 = f47 31239；港 14277944 股 = f47 14277944</td></tr>
 *   <tr><td>31</td><td>涨跌</td><td>f169</td><td>-14.24 = f169 -14.24</td></tr>
 *   <tr><td>32</td><td>涨跌幅 %</td><td>f170</td><td>-1.14 = f170 -1.14</td></tr>
 *   <tr><td>33 / 34</td><td>最高 / 最低</td><td>f44 / f45</td><td>1256.13 / 1231.05 同值</td></tr>
 *   <tr><td>37</td><td>成交额：A 股<b>万元</b>（×10000 换算元）；港股<b>已是元</b>（不换算）</td><td>f48</td>
 *       <td>A 股 386731 万 ≈ f48 3867310920 元（万元粒度）；港 6237711469.338 ≈ f48 6237711360</td></tr>
 *   <tr><td>38（港 59）</td><td>换手率 %</td><td>f168</td><td>0.25 = f168 0.25；港 0.16 = f168 0.16</td></tr>
 *   <tr><td>43</td><td>振幅 %</td><td>f171</td><td>2.00 = f171 2.0</td></tr>
 *   <tr><td>52（港 39）</td><td>PE（A 股取 52：与东财 f162 茅台 17.37 / 平安 4.27 两样本<b>数值完全一致</b>，口径对齐优先；
 *       A 股 39 位 18.99 为 TTM 口径、数值不同故不映射。港股取 39：东财 HK f162 实测 '-' 缺失，腾讯可补）</td><td>f162</td>
 *       <td>17.37 = f162 17.37；4.27 = f162 4.27；港 16.02</td></tr>
 *   <tr><td>46（港 58）</td><td>PB</td><td>f167</td><td>6.15 = f167 6.15；0.47 = f167 0.47；港 3.07 ≈ f167 3.05</td></tr>
 * </table>
 *
 * <p>港股 46 位为<b>英文名</b>（如 TENCENT）非 PB——PB 在 58 位；换手在 59 位（A 股在 38 位）。索引 47 起两市场布局整体分叉，
 * 故映射按行内市场前缀（sh/sz 走 A 股位、hk 走港股位）分列取数。
 *
 * <h2>响应契约（2026-09-24 实测）</h2>
 *
 * <ul>
 *   <li>编码 GBK（content-type {@code text/html; charset=GBK}）——本客户端取 {@code byte[]} 显式解码，不依赖转换器
 *   <li>行格式 {@code v_sh600519="1~贵州茅台~...";}；批量逗号拼接（URL 编码 %2C 实测可接受）；<b>无效代码行直接缺席</b>（无错误行）
 *   <li>无 UA / Referer 要求、无 WAF 迹象（仍带浏览器 UA 防御，与东财/新浪同款）
 * </ul>
 *
 * <p>防御：垃圾行 / 字段数不足 40 的短行整行跳过记 WARN；字段值为空白或 "-"（东财短横线同款约定）时对应 f 键不产出
 * （字段映射白名单自然跳过目标字段，半行数据优于整行丢弃）；单请求标的数上限 50 分块。
 *
 * <p>超时不在此设置——弹性超时由上层 {@code ResilienceRunner} 统一兜底（ADR-0010）； HTTP 异常直接抛出，由 adapter 层按降级语义处理。
 */
@Component
public class TencentQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(TencentQuoteClient.class);

    static final String DEFAULT_QUOTE_URL = DataSourceDefaults.TENCENT_QUOTE_URL;

    /** 响应体编码（qt.gtimg.cn 实测 text/html; charset=GBK）。 */
    private static final Charset RESPONSE_CHARSET = Charset.forName("GBK");

    /** 浏览器 UA（防御性携带，与 EastMoneyHttpSupport 同款——腾讯实测无 UA 也可通）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120"
                    + " Safari/537.36";

    /** 单请求标的数上限防御（未见官方上限，按行业惯例 50 收敛；超出分块多次请求）。 */
    static final int MAX_SYMBOLS_PER_REQUEST = 50;

    /** 合法行情行最小字段数（短于该值视为垃圾/截断行整行丢弃；真实行 A 股 ~80 字段 / 港股 ~70 字段）。 */
    private static final int MIN_FIELDS_PER_ROW = 40;

    /** A 股成交额字段单位万元 → 元的换算因子（东财 f48 单位元，对齐口径）。 */
    private static final BigDecimal WAN_TO_YUAN = BigDecimal.valueOf(10_000);

    /** 内部标的码前缀 → 腾讯市场前缀（大小写不敏感换算；未列前缀不支持）。 */
    private static final Map<String, String> PREFIX_TO_TENCENT =
            Map.of("SH", "sh", "SZ", "sz", "HK", "hk");

    private final RestClient restClient;
    private final String quoteUrl;

    /** Spring 装配构造（ADR-0032）：端点缺省取 {@link DataSourceDefaults#TENCENT_QUOTE_URL} 代码内置值。 */
    @Autowired
    public TencentQuoteClient(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, DataSourceDefaults.TENCENT_QUOTE_URL);
    }

    /** 全参构造（纯构造单测指定端点）。 */
    public TencentQuoteClient(RestClient.Builder restClientBuilder, String quoteUrl) {
        this.restClient = restClientBuilder.build();
        this.quoteUrl = quoteUrl;
    }

    /**
     * 单只取数：映射为东财 f 键中间结构。
     *
     * @param tencentSymbol 腾讯符号，如 {@code sh600519} / {@code sz000001} / {@code hk00700}
     * @return f 键映射；响应无该行 / 空体时 {@link Optional#empty()}
     */
    public Optional<Map<String, Object>> fetchQuote(String tencentSymbol) {
        return fetchQuotes(List.of(tencentSymbol)).values().stream().findFirst();
    }

    /**
     * 批量取数（单请求 ≤{@value MAX_SYMBOLS_PER_REQUEST} 只自动分块）。
     *
     * @param tencentSymbols 腾讯符号列表（顺序保留，逐块顺序请求）
     * @return 键 = 响应行自带标的符号（如 {@code sh600519}）；无效符号行缺席即无键（不视为错误）
     */
    public Map<String, Map<String, Object>> fetchQuotes(List<String> tencentSymbols) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        if (tencentSymbols == null || tencentSymbols.isEmpty()) {
            return rows;
        }
        for (List<String> chunk : partition(tencentSymbols)) {
            rows.putAll(fetchChunk(chunk));
        }
        return rows;
    }

    /**
     * 内部标的码 → 腾讯符号：{@code SH600519}→{@code sh600519} / {@code SZ000001}→{@code sz000001} / {@code
     * HK00700}→{@code hk00700}（大小写不敏感）。
     *
     * @return 前缀不在 SH/SZ/HK（如板块码）或代码段为空时 {@link Optional#empty()}——调用方按无备选数据处理
     */
    public static Optional<String> toTencentSymbol(SubjectCode subjectCode) {
        String value = subjectCode.value().trim();
        if (value.length() <= 2) {
            return Optional.empty();
        }
        String tencentPrefix =
                PREFIX_TO_TENCENT.get(value.substring(0, 2).toUpperCase(Locale.ROOT));
        String code = value.substring(2);
        if (tencentPrefix == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(tencentPrefix + code.toLowerCase(Locale.ROOT));
    }

    // ---- 内部实现 ----

    private List<List<String>> partition(List<String> symbols) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += MAX_SYMBOLS_PER_REQUEST) {
            chunks.add(symbols.subList(i, Math.min(i + MAX_SYMBOLS_PER_REQUEST, symbols.size())));
        }
        return chunks;
    }

    /** 单块请求 + 解析（GBK 字节显式解码）。 */
    private Map<String, Map<String, Object>> fetchChunk(List<String> chunk) {
        String url = quoteUrl + String.join(",", chunk);
        log.debug("腾讯行情请求 symbols={}", chunk.size());
        byte[] body =
                restClient
                        .get()
                        .uri(url)
                        .accept(MediaType.TEXT_HTML)
                        .header("User-Agent", USER_AGENT)
                        .retrieve()
                        .body(byte[].class);
        return parseBody(body == null ? "" : new String(body, RESPONSE_CHARSET));
    }

    /** 解析响应体：逐行 {@link #parseLine}，垃圾/短行跳过记 WARN，键 = 行自带符号。 */
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

    /** 单行解析：{@code v_sh600519="1~...";} → (符号, f 键映射)。 */
    private Optional<ParsedRow> parseLine(String line) {
        int assignment = line.indexOf("=\"");
        if (!line.startsWith("v_") || assignment < 3 || !line.endsWith(";")) {
            log.warn("腾讯行情行格式异常，跳过 line={}", abbreviate(line));
            return Optional.empty();
        }
        String symbol = line.substring(2, assignment);
        String payload = line.substring(assignment + 2, line.length() - 2);
        String[] fields = payload.split("~", -1);
        if (fields.length < MIN_FIELDS_PER_ROW) {
            log.warn("腾讯行情行字段数不足，跳过 symbol={} fields={}", symbol, fields.length);
            return Optional.empty();
        }
        return Optional.of(new ParsedRow(symbol, mapFields(symbol, fields)));
    }

    /** 字段映射（核对表见类 Javadoc）：按行内符号前缀分 A 股 / 港股两套字段位。 */
    private Map<String, Object> mapFields(String symbol, String[] f) {
        boolean hk = symbol.startsWith("hk");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("f57", f[2]);
        row.put("f58", f[1]);
        putDecimal(row, "f43", f[3]);
        putDecimal(row, "f60", f[4]);
        putDecimal(row, "f46", f[5]);
        putDecimal(row, "f44", f[33]);
        putDecimal(row, "f45", f[34]);
        putDecimal(row, "f169", f[31]);
        putDecimal(row, "f170", f[32]);
        putLong(row, "f47", f[6]);
        putAmount(row, hk, f[37]);
        putDecimal(row, "f168", hk ? f[59] : f[38]);
        putDecimal(row, "f171", f[43]);
        putDecimal(row, "f162", hk ? f[39] : f[52]);
        putDecimal(row, "f167", hk ? f[58] : f[46]);
        return row;
    }

    /** 成交额：A 股万元 ×10000 → 元（对齐东财 f48 单位）；港股已是元直传。 */
    private void putAmount(Map<String, Object> row, boolean hk, String raw) {
        Optional<BigDecimal> value = parseDecimal(raw);
        if (value.isEmpty()) {
            return;
        }
        row.put("f48", hk ? value.get() : value.get().multiply(WAN_TO_YUAN));
    }

    private void putDecimal(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value));
    }

    /** 成交量归一为 long（港股带 ".0" 小数串，对齐东财 f47 数值口径）。 */
    private void putLong(Map<String, Object> row, String key, String raw) {
        parseDecimal(raw).ifPresent(value -> row.put(key, value.longValue()));
    }

    /** 数值解析：空白与 "-"（东财短横线缺失约定）视为字段缺失返回 empty；不可解析记 WARN 跳过该字段（半行优于整行丢弃）。 */
    private Optional<BigDecimal> parseDecimal(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(cleaned));
        } catch (NumberFormatException e) {
            log.warn("腾讯行情字段数值不可解析，跳过 value='{}'", raw);
            return Optional.empty();
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= 60 ? line : line.substring(0, 60) + "...";
    }

    /** 解析出的单行：行自带符号 + f 键映射。 */
    private record ParsedRow(String symbol, Map<String, Object> fields) {}
}
