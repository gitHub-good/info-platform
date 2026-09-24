package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.aggregation.SubjectCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * TencentQuoteClient 单测（ADR-0031 行情/估值腾讯备选源）：全 Mock 无真实外呼。
 *
 * <p>覆盖：A股单只字段映射（含成交额万元→元换算）/ GBK→UTF-8 解码 / 批量单请求 / 超 50 只分块 /
 * 港股字段映射（PE@39、PB@58、换手@59、成交额已是元不换算）/ 异常行防御（垃圾行/短行跳过）/ 值防御（空白与 "-" 字段不产出）/ 空响应体 /
 * 内部标的码换算（SH/SZ/HK，未知前缀拒绝）。
 *
 * <p>夹具取 2026-09-24 curl 实测原始行（qt.gtimg.cn），字段索引以该日与东财 push2 交叉核对为准（核对表见 TencentQuoteClient
 * Javadoc）。
 */
class TencentQuoteClientTest {

    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=";

    /** 绑定 MockRestServiceServer 的客户端与服务端持有者（一次绑一对）。 */
    private record Bound(TencentQuoteClient client, MockRestServiceServer server) {}

    @Test
    void fetchQuote_aShareLine_mapsToEastMoneyKeys() {
        Bound bound = bindSingleResponse(TencentQuoteFixtures.LINE_SH600519);

        Optional<Map<String, Object>> data = bound.client().fetchQuote("sh600519");

        assertThat(data).isPresent();
        Map<String, Object> row = data.get();
        assertThat(row.get("f57")).isEqualTo("600519");
        assertThat(row.get("f58")).isEqualTo("贵州茅台");
        assertThat((BigDecimal) row.get("f43")).isEqualByComparingTo("1237.00");
        assertThat((BigDecimal) row.get("f60")).isEqualByComparingTo("1251.24");
        assertThat((BigDecimal) row.get("f46")).isEqualByComparingTo("1250.01");
        assertThat((BigDecimal) row.get("f44")).isEqualByComparingTo("1256.13");
        assertThat((BigDecimal) row.get("f45")).isEqualByComparingTo("1231.05");
        assertThat((BigDecimal) row.get("f169")).isEqualByComparingTo("-14.24");
        assertThat((BigDecimal) row.get("f170")).isEqualByComparingTo("-1.14");
        assertThat(row.get("f47")).isEqualTo(31239L);
        // 成交额：腾讯 A 股 37 位为万元（386731），换算为元对齐东财 f48（3867310920，万元粒度）
        assertThat((BigDecimal) row.get("f48")).isEqualByComparingTo("3867310000");
        assertThat((BigDecimal) row.get("f168")).isEqualByComparingTo("0.25");
        assertThat((BigDecimal) row.get("f171")).isEqualByComparingTo("2.00");
        // A 股 PE 取 52 位（17.37，与东财 f162 两样本数值一致，ADR-0031 口径注记）；PB 取 46 位（6.15 与东财 f167 一致）
        assertThat((BigDecimal) row.get("f162")).isEqualByComparingTo("17.37");
        assertThat((BigDecimal) row.get("f167")).isEqualByComparingTo("6.15");
    }

    @Test
    void fetchQuote_gbkBody_decodesChineseName() {
        // 响应体为 GBK 字节（qt.gtimg.cn content-type: text/html; charset=GBK），客户端须显式解码而非依赖转换器
        Bound bound = bindSingleResponse(TencentQuoteFixtures.LINE_SH600519);

        Map<String, Object> row = bound.client().fetchQuote("sh600519").orElseThrow();

        assertThat(row.get("f58")).isEqualTo("贵州茅台");
    }

    @Test
    void fetchQuotes_batchJoinsSymbolsInSingleRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(TENCENT_URL + "sh600519,sz000001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                (TencentQuoteFixtures.LINE_SH600519
                                                + "\n"
                                                + TencentQuoteFixtures.LINE_SZ000001)
                                        .getBytes(TencentQuoteFixtures.GBK),
                                MediaType.TEXT_HTML));
        TencentQuoteClient client = new TencentQuoteClient(builder, TENCENT_URL);

        Map<String, Map<String, Object>> rows = client.fetchQuotes(List.of("sh600519", "sz000001"));

        server.verify();
        assertThat(rows).containsOnlyKeys("sh600519", "sz000001");
        assertThat((BigDecimal) rows.get("sz000001").get("f43")).isEqualByComparingTo("11.30");
        assertThat((BigDecimal) rows.get("sz000001").get("f162")).isEqualByComparingTo("4.27");
        assertThat((BigDecimal) rows.get("sz000001").get("f167")).isEqualByComparingTo("0.47");
    }

    @Test
    void fetchQuotes_overFiftySymbols_partitionsIntoTwoRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        List<String> firstChunk = IntStream.range(0, 50).mapToObj(i -> "sh6000" + i).toList();
        // 第 1 块 50 只回空体（无行可解析，合法防御），第 2 块 1 只回真实行
        server.expect(requestTo(TENCENT_URL + String.join(",", firstChunk)))
                .andRespond(withSuccess(new byte[0], MediaType.TEXT_HTML));
        server.expect(requestTo(TENCENT_URL + "sz000001"))
                .andRespond(
                        withSuccess(
                                TencentQuoteFixtures.LINE_SZ000001.getBytes(
                                        TencentQuoteFixtures.GBK),
                                MediaType.TEXT_HTML));
        List<String> symbols = new ArrayList<>(firstChunk);
        symbols.add("sz000001");
        TencentQuoteClient client = new TencentQuoteClient(builder, TENCENT_URL);

        Map<String, Map<String, Object>> rows = client.fetchQuotes(symbols);

        server.verify();
        assertThat(rows).containsOnlyKeys("sz000001");
    }

    @Test
    void fetchQuote_hkLine_mapsValuationFromHkIndexes() {
        Bound bound = bindSingleResponse(TencentQuoteFixtures.LINE_HK00700);

        Map<String, Object> row = bound.client().fetchQuote("hk00700").orElseThrow();

        assertThat(row.get("f57")).isEqualTo("00700");
        assertThat(row.get("f58")).isEqualTo("腾讯控股");
        assertThat((BigDecimal) row.get("f43")).isEqualByComparingTo("438.400");
        // 港股估值字段位与 A 股不同：PE 取 39 位（16.02，东财 HK f162='-' 缺失，腾讯可补）、PB 取 58 位（3.07≈东财 3.05）
        assertThat((BigDecimal) row.get("f162")).isEqualByComparingTo("16.02");
        assertThat((BigDecimal) row.get("f167")).isEqualByComparingTo("3.07");
        // 港股换手取 59 位（0.16 与东财 f168 数值一致）；成交量 6 位为股、去掉 ".0" 对齐东财 f47 数值口径
        assertThat((BigDecimal) row.get("f168")).isEqualByComparingTo("0.16");
        assertThat(row.get("f47")).isEqualTo(14277944L);
        // 港股 37 位已是元（6237711469.338≈东财 f48 6237711360），不换算
        assertThat((BigDecimal) row.get("f48")).isEqualByComparingTo("6237711469.338");
    }

    @Test
    void fetchQuotes_malformedLines_skippedButValidLinesParsed() {
        String body =
                "garbage line\n"
                        + "v_x=\"too~few~fields\";\n"
                        + TencentQuoteFixtures.LINE_SH600519
                        + "\n";
        Bound bound = bindSingleResponse(body);

        Map<String, Map<String, Object>> rows = bound.client().fetchQuotes(List.of("sh600519"));

        assertThat(rows).containsOnlyKeys("sh600519");
    }

    @Test
    void fetchQuote_emptyBody_returnsEmpty() {
        Bound bound = bindSingleResponse("");

        Optional<Map<String, Object>> data = bound.client().fetchQuote("sh600519");

        assertThat(data).isEmpty();
    }

    @Test
    void fetchQuote_dashOrBlankValuationFields_notProduced() {
        // 估值字段可能为 "-" 或空白（东财短横线同款约定）——对应 f 键不产出，字段映射白名单自然跳过目标字段
        String[] f = placeholderFields(60);
        f[46] = "-";
        f[52] = "";
        Bound bound = bindSingleResponse("v_sh600519=\"" + String.join("~", f) + "\";");

        Map<String, Object> row = bound.client().fetchQuote("sh600519").orElseThrow();

        assertThat(row).doesNotContainKeys("f167", "f162");
        assertThat((BigDecimal) row.get("f43")).isEqualByComparingTo("10.00");
    }

    @Test
    void toTencentSymbol_convertsInternalSubjectCode() {
        assertThat(TencentQuoteClient.toTencentSymbol(SubjectCode.of("SH600519")))
                .contains("sh600519");
        assertThat(TencentQuoteClient.toTencentSymbol(SubjectCode.of("sz000001")))
                .contains("sz000001");
        assertThat(TencentQuoteClient.toTencentSymbol(SubjectCode.of("HK00700")))
                .contains("hk00700");
        // 腾讯不支持的前缀（如板块）拒绝换算——调用方按无备选数据处理
        assertThat(TencentQuoteClient.toTencentSymbol(SubjectCode.of("BK0475"))).isEmpty();
        assertThat(TencentQuoteClient.toTencentSymbol(SubjectCode.of("SH"))).isEmpty();
    }

    // ---- helpers ----

    /** 绑定客户端与仅期待一次任意 q= 请求的 mock 服务端（响应体按 GBK 编码回给客户端）。 */
    private Bound bindSingleResponse(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("q=")))
                .andRespond(
                        withSuccess(body.getBytes(TencentQuoteFixtures.GBK), MediaType.TEXT_HTML));
        return new Bound(new TencentQuoteClient(builder, TENCENT_URL), server);
    }

    /** 构造占位字段数组：idx3 现价 10.00、其余估值位填合法占位值，供个别字段置 "-" / 空白的用例改写。 */
    private static String[] placeholderFields(int size) {
        String[] f = new String[size];
        java.util.Arrays.fill(f, "1.00");
        f[0] = "1";
        f[1] = "占位";
        f[2] = "600519";
        f[3] = "10.00";
        return f;
    }
}
