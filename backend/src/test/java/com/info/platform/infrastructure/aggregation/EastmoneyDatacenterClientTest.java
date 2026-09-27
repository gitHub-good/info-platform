package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.application.markettop.IndustryBoardSource.IndustryBoardRow;
import com.info.platform.infrastructure.common.ResilienceRunner;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * EastmoneyDatacenterClient 单测（M21 T180 通道 A）：MockRestServiceServer 模拟 datacenter {@code
 * RPT_WEB_RESPREDICT} 响应——<b>禁止真实外呼 datacenter</b>（04 测试规范；实测留档归架构 §1.2）。
 *
 * <p>覆盖（方案 §4.1.2 + §6 测试要点）：请求参数契约（reportName/columns/pageNumber/pageSize/sortColumns + UA +
 * Referer）/ 分页遍历与 count 完整性 / text/plain JSON 容错 / 空页终止后 count 不符放弃 / result 缺失放弃 / 空板块行跳过 / 单页覆盖
 * count 不多发请求。
 */
class EastmoneyDatacenterClientTest {

    private static final String REPORT_URL = "https://datacenter-web.eastmoney.com/api/data/v1/get";

    /** 页间隔测试值：够断言「页间确有 sleep」又不拖慢测试。 */
    private static final long TEST_INTERVAL_MILLIS = 10;

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
    void fetchIndustryBoards_twoPages_assemblesRowsAndValidatesCount() {
        List<IndustryBoardRow> rows =
                fetchWithPages(
                        server -> {
                            server.expect(
                                            requestTo(
                                                    containsString(
                                                            "reportName=RPT_WEB_RESPREDICT")))
                                    .andExpect(
                                            requestTo(
                                                    containsString(
                                                            "columns=SECURITY_CODE,INDUSTRY_BOARD")))
                                    .andExpect(requestTo(containsString("pageNumber=1")))
                                    .andExpect(requestTo(containsString("pageSize=100")))
                                    .andExpect(
                                            requestTo(containsString("sortColumns=SECURITY_CODE")))
                                    .andExpect(requestTo(containsString("sortTypes=1")))
                                    .andExpect(method(HttpMethod.GET))
                                    // ISSUE-A：datacenter WAF 裸 UA 断连——须浏览器
                                    // UA（EastMoneyFinanceClient 同款）
                                    .andExpect(header("User-Agent", containsString("Mozilla")))
                                    // datacenter 软限频要求来源页 Referer（实测缺 Referer 不可用）
                                    .andExpect(header("Referer", "https://data.eastmoney.com/"))
                                    .andRespond(
                                            withSuccess(
                                                    page(
                                                            3,
                                                            row("600519", "白酒Ⅱ"),
                                                            row("000001", "银行Ⅱ")),
                                                    MediaType.APPLICATION_JSON));
                            server.expect(requestTo(containsString("pageNumber=2")))
                                    .andRespond(
                                            withSuccess(
                                                    page(3, row("300024", "通用设备")),
                                                    MediaType.TEXT_PLAIN)); // ISSUE-B：text/plain
                            // JSON 容错
                        });

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).securityCode()).isEqualTo("600519");
        assertThat(rows.get(0).industryBoard()).isEqualTo("白酒Ⅱ");
        assertThat(rows.get(2).industryBoard()).isEqualTo("通用设备");
    }

    @Test
    void fetchIndustryBoards_singlePageCoversCount_noExtraRequest() {
        List<IndustryBoardRow> rows =
                fetchWithPages(
                        server ->
                                server.expect(requestTo(containsString("pageNumber=1")))
                                        .andRespond(
                                                withSuccess(
                                                        page(1, row("600519", "白酒Ⅱ")),
                                                        MediaType.APPLICATION_JSON)));

        // 单页即覆盖 count：不发第二页请求（server.verify 校验无多余请求）
        assertThat(rows).hasSize(1);
    }

    @Test
    void fetchIndustryBoards_emptyPageThenCountMismatch_abandonsChannel() {
        // 空页终止翻页后累计 ≠ count → 完整性校验失败（调用方按通道降级，不写半截数据）
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        server -> {
                                            server.expect(requestTo(containsString("pageNumber=1")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(3, row("600519", "白酒Ⅱ")),
                                                                    MediaType.APPLICATION_JSON));
                                            server.expect(requestTo(containsString("pageNumber=2")))
                                                    .andRespond(
                                                            withSuccess(
                                                                    page(3),
                                                                    MediaType.APPLICATION_JSON));
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("完整性");
    }

    @Test
    void fetchIndustryBoards_missingResultNode_abandonsChannel() {
        assertThatThrownBy(
                        () ->
                                fetchWithPages(
                                        server ->
                                                server.expect(
                                                                requestTo(
                                                                        containsString(
                                                                                "pageNumber=1")))
                                                        .andRespond(
                                                                withSuccess(
                                                                        "{\"success\":true,\"result\":null,\"data\":null}",
                                                                        MediaType
                                                                                .APPLICATION_JSON))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("result 节点");
    }

    @Test
    void fetchIndustryBoards_blankBoardOrCode_rowsSkipped() {
        // 空板块行静默跳过 / 缺代码行丢弃（count=0：全部跳过后即终止，行数缺口口径由完整性校验兜底）
        List<IndustryBoardRow> rows =
                fetchWithPages(
                        server ->
                                server.expect(requestTo(containsString("pageNumber=1")))
                                        .andRespond(
                                                withSuccess(
                                                        page(
                                                                0,
                                                                "{\"SECURITY_CODE\":\"600519\"}",
                                                                row("000001", "")),
                                                        MediaType.APPLICATION_JSON)));

        assertThat(rows).isEmpty();
    }

    // ---- helpers ----

    /** 构造绑定 MockRestServiceServer 的客户端并执行；响应与请求校验由 responseSetter 设置（verify 兜底无多余请求）。 */
    private List<IndustryBoardRow> fetchWithPages(Consumer<MockRestServiceServer> responseSetter) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EastmoneyDatacenterClient client =
                new EastmoneyDatacenterClient(builder, runner, REPORT_URL, TEST_INTERVAL_MILLIS);
        responseSetter.accept(server);
        List<IndustryBoardRow> rows = client.fetchIndustryBoards();
        server.verify();
        return rows;
    }

    /** 构造 datacenter 响应体（结构按 2026-09-27 实测：result.count + result.data[]）。 */
    private static String page(int count, String... rows) {
        StringBuilder sb =
                new StringBuilder("{\"result\":{\"count\":").append(count).append(",\"data\":[");
        for (int i = 0; i < rows.length; i++) {
            sb.append(i == 0 ? "" : ",").append(rows[i]);
        }
        return sb.append("]},\"success\":true,\"code\":0}").toString();
    }

    private static String row(String code, String board) {
        return "{\"SECURITY_CODE\":\"" + code + "\",\"INDUSTRY_BOARD\":\"" + board + "\"}";
    }
}
