package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.aggregation.SubjectCode;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * SinaQuoteClient 单测（M29 T252 备链，Spike-E E-2b 实测布局）：全 Mock 无真实外呼。
 *
 * <p>覆盖：rt_hk 港股实时行映射（现价/昨收/今开/高低/涨跌/量额/PE/f30 拼接）、gb_ 美股行映射（无昨收位白名单跳过、市值 USD 原值 ÷1e8 归一亿对齐腾讯 @44
 * 单位）、GBK 解码、Referer 必带头、批量单请求、异常行防御、内部标的码换算（rt_hk 前缀 / gb_ 小写 ticker / 不支持前缀拒绝）。
 *
 * <p>夹具取 2026-09-29 curl 实测原始行（hq.sinajs.cn），字段索引与腾讯同刻同值交叉核对（核对表见 SinaQuoteClient Javadoc）。
 */
class SinaQuoteClientTest {

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";

    private static final Charset GBK = Charset.forName("GBK");

    /** 2026-09-29 实测：腾讯控股 rt_hk 实时增强行（无标签逗号位布局，19+ 位）。 */
    static final String LINE_RT_HK00700 =
            "var hq_str_rt_hk00700=\"TENCENT,腾讯控股,439.400,439.800,439.400,431.600,432.000,-7.800,-1.774,"
                    + "432.000,432.200,7808702026.492,18015236,15.701,0.000,675.134,411.000,"
                    + "2026/09/29,16:08:08,100|0,N|Y|Y,432.200|410.600|453.800,0|||0.000|0.000|0.000, |0,Y\";";

    /** 2026-09-29 实测：苹果 gb_ 美股行（30+ 位，无昨收位；市值@12 为 USD 原值）。 */
    static final String LINE_GB_AAPL =
            "var hq_str_gb_aapl=\"苹果,338.4000,-0.78,2026-09-29 19:38:15,-2.6700,340.3700,342.9880,338.0400,"
                    + "345.3400,242.8900,32820844,38594329,4938671029752,8.30,40.770000,0.00,0.00,0.00,0.00,"
                    + "14594181530,63,336.9700,-0.42,-1.43,Sep 29 07:38AM EDT,Sep 28 04:00PM EDT,"
                    + "341.0700,180625,1,2026,11153512320.7684,338.4000,336.0000,60852416.7947,337.3000,338.4000\";";

    @Test
    void fetchQuote_rtHkLine_mapsToEastMoneyKeys() {
        MockRestServiceServer server;
        SinaQuoteClient client;
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SINA_URL + "rt_hk00700"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Referer", "https://finance.sina.com.cn"))
                .andRespond(withSuccess(LINE_RT_HK00700.getBytes(GBK), MediaType.TEXT_HTML));
        client = new SinaQuoteClient(builder, SINA_URL);

        Map<String, Map<String, Object>> rows = client.fetchQuotes(List.of("rt_hk00700"));

        server.verify();
        Map<String, Object> row = rows.get("rt_hk00700");
        assertThat(row).isNotNull();
        assertThat(row.get("f58")).isEqualTo("腾讯控股");
        assertThat((BigDecimal) row.get("f43")).isEqualByComparingTo("432.000");
        assertThat((BigDecimal) row.get("f60")).isEqualByComparingTo("439.800");
        assertThat((BigDecimal) row.get("f46")).isEqualByComparingTo("439.400");
        assertThat((BigDecimal) row.get("f44")).isEqualByComparingTo("439.400");
        assertThat((BigDecimal) row.get("f45")).isEqualByComparingTo("431.600");
        assertThat((BigDecimal) row.get("f169")).isEqualByComparingTo("-7.800");
        assertThat((BigDecimal) row.get("f170")).isEqualByComparingTo("-1.774");
        assertThat(row.get("f47")).isEqualTo(18015236L);
        // 港股成交额已是 HKD 元直传（与腾讯同刻同值交叉一致）
        assertThat((BigDecimal) row.get("f48")).isEqualByComparingTo("7808702026.492");
        assertThat((BigDecimal) row.get("f162")).isEqualByComparingTo("15.701");
        // f30 = 日期@17 + 时间@18 拼接
        assertThat(row.get("f30")).isEqualTo("2026/09/29 16:08:08");
        // rt_hk 行无市值位——market_cap 不产出（白名单语义，币种由适配层按符号族派生）
        assertThat(row).doesNotContainKey("market_cap").doesNotContainKey("currency");
    }

    @Test
    void fetchQuote_gbUsLine_mapsWithMarketCapNormalizedToYi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SINA_URL + "gb_aapl"))
                .andRespond(withSuccess(LINE_GB_AAPL.getBytes(GBK), MediaType.TEXT_HTML));
        SinaQuoteClient client = new SinaQuoteClient(builder, SINA_URL);

        Map<String, Map<String, Object>> rows = client.fetchQuotes(List.of("gb_aapl"));

        Map<String, Object> row = rows.get("gb_aapl");
        assertThat(row).isNotNull();
        assertThat(row.get("f58")).isEqualTo("苹果");
        assertThat((BigDecimal) row.get("f43")).isEqualByComparingTo("338.4000");
        assertThat((BigDecimal) row.get("f170")).isEqualByComparingTo("-0.78");
        assertThat((BigDecimal) row.get("f169")).isEqualByComparingTo("-2.6700");
        assertThat((BigDecimal) row.get("f46")).isEqualByComparingTo("340.3700");
        assertThat((BigDecimal) row.get("f44")).isEqualByComparingTo("342.9880");
        assertThat((BigDecimal) row.get("f45")).isEqualByComparingTo("338.0400");
        assertThat(row.get("f47")).isEqualTo(32820844L);
        assertThat((BigDecimal) row.get("f162")).isEqualByComparingTo("40.770000");
        // 市值 USD 原值 4938671029752 ÷ 1e8 → 亿 USD（与腾讯 @44 的 49356.00844 交叉一致量级）
        assertThat((BigDecimal) row.get("market_cap")).isEqualByComparingTo("49386.7103");
        // gb_ 行无昨收位（f60 白名单跳过）；f30 为北京时间墙钟
        assertThat(row).doesNotContainKey("f60");
        assertThat(row.get("f30")).isEqualTo("2026-09-29 19:38:15");
    }

    @Test
    void fetchQuotes_mixedBatch_singleRequestWithReferer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SINA_URL + "rt_hk00700,gb_aapl"))
                .andExpect(header("Referer", "https://finance.sina.com.cn"))
                .andRespond(
                        withSuccess(
                                (LINE_RT_HK00700 + "\n" + LINE_GB_AAPL).getBytes(GBK),
                                MediaType.TEXT_HTML));
        SinaQuoteClient client = new SinaQuoteClient(builder, SINA_URL);

        Map<String, Map<String, Object>> rows =
                client.fetchQuotes(List.of("rt_hk00700", "gb_aapl"));

        server.verify();
        assertThat(rows).containsOnlyKeys("rt_hk00700", "gb_aapl");
    }

    @Test
    void fetchQuotes_malformedOrForeignLines_skipped() {
        String body =
                "garbage line\n"
                        + "var hq_str_sh600519=\"1~few~fields\";\n" // 非港美符号族跳过
                        + "var hq_str_rt_hk00700=\"too,few\";\n" // 短行跳过
                        + LINE_GB_AAPL
                        + "\n";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("list=")))
                .andRespond(withSuccess(body.getBytes(GBK), MediaType.TEXT_HTML));
        SinaQuoteClient client = new SinaQuoteClient(builder, SINA_URL);

        Map<String, Map<String, Object>> rows = client.fetchQuotes(List.of("gb_aapl"));

        assertThat(rows).containsOnlyKeys("gb_aapl");
    }

    @Test
    void fetchQuotes_emptyAndNullBody_returnsEmptyMap() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("list=")))
                .andRespond(withSuccess(new byte[0], MediaType.TEXT_HTML));
        SinaQuoteClient client = new SinaQuoteClient(builder, SINA_URL);

        assertThat(client.fetchQuotes(List.of("rt_hk00700"))).isEmpty();
        assertThat(client.fetchQuotes(List.of())).isEmpty();
        assertThat(client.fetchQuotes(null)).isEmpty();
    }

    @Test
    void toSinaSymbol_convertsHkRealtimeAndUsLowercase() {
        // 港股必须 rt_hk 实时前缀（裸 hk_ 延迟 15 分钟不用）；美股 gb_ + 小写 ticker（与腾讯 US 大写相反）
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("HK00700"))).contains("rt_hk00700");
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("USAAPL"))).contains("gb_aapl");
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("usMSFT"))).contains("gb_msft");
        // 不支持前缀拒绝换算——调用方按无备选数据处理
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("SH600519")))
                .isEqualTo(Optional.empty());
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("BK0475"))).isEmpty();
        assertThat(SinaQuoteClient.toSinaSymbol(SubjectCode.of("US"))).isEmpty();
    }
}
