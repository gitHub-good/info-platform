package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.infrastructure.common.ResilienceRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * TencentBoardRankClient 单测（M27 T242 通道 B）：MockRestServiceServer 模拟 getRank
 * 板块排行响应——<b>禁止真实外呼</b>（实测留档 归预检/架构 §1.2；fixture 取 2026-09-28 预检响应字段契约）。
 *
 * <p>覆盖（方案 §6 测试要点 + §4.2.1 契约）：请求参数（board_type=hy / sort_type=price / count=100 + UA + Referer
 * gu.qq.com）/ 字段映射（zdf/zdf_d5/zljlr×10⁴/zgb "57/122" 两数解析/zsz×10⁸/lzg 领涨股去市场前缀）/ 31 名漂移
 * fail-fast（少名/多名）/ rank_list 缺失放弃。
 */
class TencentBoardRankClientTest {

    private static final String RANK_URL =
            "https://proxy.finance.qq.com/cgi/cgi-bin/rank/pt/getRank";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T07:00:02Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService exec;

    private ResilienceRunner runner;

    @BeforeEach
    void setUp() {
        exec = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        runner = new ResilienceRunner(exec);
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    /** 预检实测响应形态的最小 fixture（rank_list 节点 + 31 申万行业名）。 */
    private static String body(java.util.List<String> names, Map<String, Object> overrides)
            throws Exception {
        StringBuilder rows = new StringBuilder();
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", "bk_" + name.hashCode());
            row.put("name", name);
            row.put("stock_type", "BK-HY-1");
            row.put("zdf", -0.17);
            row.put("zdf_d5", -0.82);
            row.put("zljlr", -16835.63);
            row.put("zgb", "57/122");
            row.put("zsz", 36103.24);
            row.put("ltsz", 35043.50);
            Map<String, Object> lzg = new LinkedHashMap<>();
            lzg.put("code", "sh601579");
            lzg.put("name", "会稽山");
            lzg.put("zdf", "9.99");
            row.put("lzg", lzg);
            row.putAll(overrides);
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append(new ObjectMapper().writeValueAsString(row));
        }
        return "{\"code\":0,\"msg\":\"\",\"data\":{\"count\":"
                + names.size()
                + ",\"rank_list\":["
                + rows
                + "]}}";
    }

    private static java.util.List<String> all31SwIndustries() {
        return java.util.List.copyOf(
                com.info.platform.domain.analysis.IndustryCategory.SW_INDUSTRIES);
    }

    @Test
    void fetch_mapsFields_unitsAndLeaderStock() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("board_type=hy")))
                .andExpect(requestTo(containsString("sort_type=price")))
                .andExpect(requestTo(containsString("count=100")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", containsString("Mozilla")))
                .andExpect(header("Referer", "https://gu.qq.com/"))
                .andRespond(
                        withSuccess(
                                body(all31SwIndustries(), Map.of()), MediaType.APPLICATION_JSON));

        // Act
        IndustryQuoteBatch batch =
                new TencentBoardRankClient(builder, runner, FIXED_CLOCK, RANK_URL).fetch();

        // Assert：31 行直出 TENCENT_DIRECT；单位换算（万→元、亿→元）与 zgb 两数、lzg 前缀剥离
        assertThat(batch.source()).isEqualTo("tencent-rank");
        assertThat(batch.industries()).hasSize(31);
        assertThat(batch.boards()).isEmpty();
        com.info.platform.domain.mainline.IndustryQuote food =
                batch.industries().stream()
                        .filter(q -> q.industry().equals("食品饮料"))
                        .findFirst()
                        .orElseThrow();
        assertThat(food.pctDay()).isEqualTo(-0.17);
        assertThat(food.pctD5()).isEqualTo(-0.82);
        assertThat(food.upCount()).isEqualTo(57);
        assertThat(food.downCount()).isEqualTo(122);
        assertThat(food.mainNetFlow())
                .isCloseTo(-16835.63 * 10_000d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(food.totalMv())
                .isCloseTo(36103.24 * 100_000_000d, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(food.aggMethod()).isEqualTo("TENCENT_DIRECT");
        assertThat(food.leaderStock()).isNotNull();
        assertThat(food.leaderStock().code()).isEqualTo("601579");
        assertThat(food.leaderStock().name()).isEqualTo("会稽山");
        assertThat(food.leaderStock().pct()).isEqualTo(9.99);
        server.verify();
    }

    @Test
    void fetch_nameDriftMissing_failsFastForRoundDegradation() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 30 名（少 1 名）→ 31 名校验失败 → 本轮降级（调用方走双通道全败路径）
        java.util.List<String> thirty = new java.util.ArrayList<>(all31SwIndustries());
        thirty.remove(0);
        server.expect(requestTo(containsString("board_type=hy")))
                .andRespond(withSuccess(body(thirty, Map.of()), MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new TencentBoardRankClient(builder, runner, FIXED_CLOCK, RANK_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("31");
    }

    @Test
    void fetch_unknownName_failsFast() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        java.util.List<String> drifted = new java.util.ArrayList<>(all31SwIndustries());
        drifted.set(0, "神秘新行业");
        server.expect(requestTo(containsString("board_type=hy")))
                .andRespond(withSuccess(body(drifted, Map.of()), MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new TencentBoardRankClient(builder, runner, FIXED_CLOCK, RANK_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("神秘新行业");
    }

    @Test
    void fetch_rankListMissing_throws() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("board_type=hy")))
                .andRespond(
                        withSuccess(
                                "{\"code\":0,\"data\":{\"count\":0}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new TencentBoardRankClient(builder, runner, FIXED_CLOCK, RANK_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rank_list");
    }
}
