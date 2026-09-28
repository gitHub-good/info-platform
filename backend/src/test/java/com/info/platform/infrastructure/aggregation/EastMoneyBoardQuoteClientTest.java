package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.mainline.IndustryQuoteBatch;
import com.info.platform.infrastructure.common.ResilienceRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * EastMoneyBoardQuoteClient 单测（M27 T242 通道 A）：MockRestServiceServer 模拟 push2 clist 板块行情响应——
 * <b>禁止真实外呼 push2</b>（Spike-C C-1 封禁期；字段契约按方案 §4.2.1 写定 + 单测锁契约 + 首跑复核留档，04 测试规范）。
 *
 * <p>覆盖（方案 §6 测试要点）：请求参数契约（fs=m:90+t:2 / fields / pz=100 + UA）/ 字段映射（f14/f3/f62/f104/f105/f20）/
 * swPrimaryOf 未收录跳过 + WARN 计数 / 聚合输出（CAP_WEIGHTED/EQUAL）/ total 完整性校验 / data 节点缺失放弃 / 失败重试耗尽上抛。
 */
class EastMoneyBoardQuoteClientTest {

    private static final String BOARD_URL = "https://push2.eastmoney.com/api/qt/clist/get";

    /** 固定时钟（quoteTime 确定性断言）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-28T07:00:02Z"), ZoneOffset.UTC);

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

    /** 与 EastmoneyDatacenterClientTest 同款：server 绑定在被测客户端实际使用的 builder 上（防静态 bindTo 误用）。 */
    private MockRestServiceServer newServer(RestClient.Builder builder) {
        return MockRestServiceServer.bindTo(builder).build();
    }

    /** push2 clist 响应体（diff 数组形态；total 完整性锚）。 */
    private static String body(String rowsJson, int total) {
        return "{\"rc\":0,\"rt\":6,\"svr\":1,\"lt\":1,\"full\":1,\"data\":{\"total\":"
                + total
                + ",\"diff\":["
                + rowsJson
                + "]}}";
    }

    private static String row(
            String code, String name, Object f3, Object f62, Object f104, Object f105, Object f20) {
        return "{\"f12\":\""
                + code
                + "\",\"f14\":\""
                + name
                + "\",\"f3\":"
                + f3
                + ",\"f62\":"
                + f62
                + ",\"f104\":"
                + f104
                + ",\"f105\":"
                + f105
                + ",\"f20\":"
                + f20
                + "}";
    }

    @Test
    void fetch_mapsFields_aggregatesToIndustries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = newServer(builder);
        // Arrange：银行Ⅱ 单板块行业（CAP_WEIGHTED）+ 电子两板块市值加权 + 一条未收录板块行（安全侧跳过）
        server.expect(requestTo(containsString("clist/get")))
                .andExpect(requestTo(containsString("fs=m:90+t:2")))
                .andExpect(requestTo(containsString("fields=f12,f14,f3,f62,f104,f105,f20")))
                .andExpect(requestTo(containsString("pz=100")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", containsString("Mozilla")))
                .andRespond(
                        withSuccess(
                                body(
                                        row("BK0475", "银行Ⅱ", 0.56, 1.2e9, 30, 12, 9.5e12)
                                                + ","
                                                + row("BK1036", "半导体", 2.5, 3e9, 120, 40, 3e12)
                                                + ","
                                                + row("BK0459", "消费电子", -1.0, -2e9, 40, 160, 1e12)
                                                + ","
                                                + row("BK0999", "未收录板块X", 9.9, 0, 1, 1, 1e10),
                                        4),
                                MediaType.APPLICATION_JSON));

        // Act
        IndustryQuoteBatch batch =
                new EastMoneyBoardQuoteClient(builder, runner, FIXED_CLOCK, BOARD_URL).fetch();

        // Assert：板块行 3（未收录行跳过留计数）；行业行 2；聚合口径
        assertThat(batch.source()).isEqualTo("eastmoney-push2");
        assertThat(batch.unmappedBoards()).isEqualTo(1);
        assertThat(batch.boards()).hasSize(3);
        assertThat(batch.industries()).hasSize(2);
        assertThat(batch.industries())
                .anySatisfy(
                        q -> {
                            assertThat(q.industry()).isEqualTo("电子");
                            // (3e12×2.5 + 1e12×(-1.0)) / 4e12 = 1.625
                            assertThat(q.pctDay())
                                    .isCloseTo(1.625, org.assertj.core.data.Offset.offset(1e-9));
                            assertThat(q.upCount()).isEqualTo(160);
                            assertThat(q.downCount()).isEqualTo(200);
                            assertThat(q.aggMethod()).isEqualTo("CAP_WEIGHTED");
                        });
        assertThat(batch.quoteTime()).isNotBlank();
        server.verify();
    }

    @Test
    void fetch_resultMissing_throwsForChannelFallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = newServer(builder);
        server.expect(requestTo(containsString("clist/get")))
                .andRespond(withSuccess("{\"rc\":0,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new EastMoneyBoardQuoteClient(
                                                builder, runner, FIXED_CLOCK, BOARD_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data");
    }

    @Test
    void fetch_countMismatch_throwsForChannelFallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = newServer(builder);
        server.expect(requestTo(containsString("clist/get")))
                .andRespond(
                        withSuccess(
                                body(row("BK0475", "银行Ⅱ", 0.56, 1e9, 30, 12, 9.5e12), 5),
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new EastMoneyBoardQuoteClient(
                                                builder, runner, FIXED_CLOCK, BOARD_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    @Test
    void fetch_serverErrorAfterRetry_throwsIllegalState() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = newServer(builder);
        // 重试 1 → 两次 500 后耗尽上抛（调用方按通道降级）
        server.expect(requestTo(containsString("clist/get"))).andRespond(withServerError());
        server.expect(requestTo(containsString("clist/get"))).andRespond(withServerError());

        assertThatThrownBy(
                        () ->
                                new EastMoneyBoardQuoteClient(
                                                builder, runner, FIXED_CLOCK, BOARD_URL)
                                        .fetch())
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
