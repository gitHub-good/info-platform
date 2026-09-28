package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.application.mainline.AttentionSource.HolderChangeSummary;
import com.info.platform.application.mainline.AttentionSource.LhbSummary;
import com.info.platform.infrastructure.common.ResilienceRunner;
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
 * EastmoneyAttentionClient 单测（M27 T244）：MockRestServiceServer 模拟 datacenter
 * 两报表——<b>禁止真实外呼</b>（实测参数形态归 Spike-D 附录 B；04 测试规范）。
 *
 * <p>覆盖（方案 §4.4.4）：请求参数契约（reportName/filter 复合过滤形态 + UA + Referer datacenter）/ 龙虎榜 count 直出 + 首行
 * EXPLANATION/TRADE_DATE / 增减持按 DIRECTION 分组净方向 / result 缺失降级异常。
 */
class EastmoneyAttentionClientTest {

    private static final String REPORT_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";

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

    @Test
    void fetchLhbSummary_countAndLatestRow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Arrange：Spike-D D-4 实测响应形态（count=3 + 首行 EXPLANATION/TRADE_DATE）
        server.expect(requestTo(containsString("reportName=RPT_DAILYBILLBOARD_DETAILSNEW")))
                .andExpect(requestTo(containsString("columns=ALL")))
                .andExpect(requestTo(containsString("pageSize=1")))
                .andExpect(requestTo(containsString("sortColumns=TRADE_DATE")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", containsString("Mozilla")))
                .andExpect(header("Referer", "https://data.eastmoney.com/"))
                .andRespond(
                        withSuccess(
                                "{\"success\":true,\"result\":{\"count\":3,\"data\":["
                                        + "{\"TRADE_DATE\":\"2026-09-28 00:00:00\","
                                        + "\"EXPLANATION\":\"连续三个交易日内，涨幅偏离值累计达到20%的证券\","
                                        + "\"BILLBOARD_NET_AMT\":12345678.9}]}}",
                                MediaType.APPLICATION_JSON));

        // Act
        LhbSummary summary =
                new EastmoneyAttentionClient(builder, runner, REPORT_URL)
                        .fetchLhbSummary("1", "2026-08-23");

        // Assert
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.latestDate()).isEqualTo("2026-09-28 00:00:00");
        assertThat(summary.reason()).contains("涨幅偏离值");
        server.verify();
    }

    @Test
    void fetchHolderChangeSummary_directionGroupsNet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("reportName=RPT_SHARE_HOLDER_INCREASE")))
                .andExpect(requestTo(containsString("sortColumns=NOTICE_DATE")))
                .andRespond(
                        withSuccess(
                                "{\"result\":{\"count\":3,\"data\":["
                                        + "{\"DIRECTION\":\"增持\"},"
                                        + "{\"DIRECTION\":\"增持\"},"
                                        + "{\"DIRECTION\":\"减持\"}]}}",
                                MediaType.APPLICATION_JSON));

        HolderChangeSummary summary =
                new EastmoneyAttentionClient(builder, runner, REPORT_URL)
                        .fetchHolderChangeSummary("600519", "2026-08-29");

        assertThat(summary.increaseCount()).isEqualTo(2);
        assertThat(summary.decreaseCount()).isEqualTo(1);
        assertThat(summary.netDirection()).isEqualTo("净增持");
        server.verify();
    }

    @Test
    void fetchHolderChangeSummary_zeroRows_balanced() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("reportName=RPT_SHARE_HOLDER_INCREASE")))
                .andRespond(
                        withSuccess(
                                "{\"result\":{\"count\":0,\"data\":[]}}",
                                MediaType.APPLICATION_JSON));

        HolderChangeSummary summary =
                new EastmoneyAttentionClient(builder, runner, REPORT_URL)
                        .fetchHolderChangeSummary("600519", "2026-08-29");

        assertThat(summary.netDirection()).isEqualTo("均衡");
        assertThat(summary.increaseCount()).isZero();
    }

    @Test
    void fetch_resultMissing_throwsForBadgeDegradation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("reportName=")))
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                        () ->
                                new EastmoneyAttentionClient(builder, runner, REPORT_URL)
                                        .fetchLhbSummary("600519", "2026-08-29"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("result");
    }
}
