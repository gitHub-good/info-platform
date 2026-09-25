package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.info.platform.domain.feed.FetchContext;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.RawFeedItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 东财宏观适配器单测（M14 T110，REQ 拍板一 #10 序列条目化裁量）：CPI 真实样本（2026-09-25 预检，REPORT_DATE 降序）的
 * 标题/摘要/externalId/数据期口径 + cursorType=NONE 不产出游标 + 源侧报错与全字典失败语义 + URL 显式降序参数。
 */
class EastmoneyMacroAdapterTest {

    private final EastmoneyMacroAdapter adapter =
            new EastmoneyMacroAdapter(RestClient.builder().build());

    private static InfoSource macroSource() {
        InfoSourceCatalog.PresetEntry entry =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals("em_macro_indicators"))
                        .findFirst()
                        .orElseThrow();
        return InfoSource.create(
                entry.sourceCode(),
                entry.name(),
                entry.category(),
                entry.adapterType(),
                entry.adapterRef(),
                entry.endpoint(),
                new SourceConfigCodec().parse(entry.configJson()),
                entry.intervalMinutes(),
                true,
                true);
    }

    private static String fixture() {
        try (InputStream in =
                EastmoneyMacroAdapterTest.class
                        .getClassLoader()
                        .getResourceAsStream("feed/em-macro-cpi-sample.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失: feed/em-macro-cpi-sample.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 读取失败", e);
        }
    }

    @Test
    void parseReport_cpiRows_itemizedWithTitleSummaryAndPeriodCursorNone() {
        InfoSource source = macroSource();
        assertThat(source.getConfig().effectiveCursorType().name()).isEqualTo("NONE");

        List<RawFeedItem> items =
                adapter.parseReport(
                        fixture(),
                        source,
                        new EastmoneyMacroAdapter.MacroReport("RPT_ECONOMY_CPI", "CPI"));

        // 真实样本 3 行（2026-08/07/06 月度序列，newest-first）
        assertThat(items).hasSize(3);
        RawFeedItem newest = items.get(0);
        assertThat(newest.externalId()).isEqualTo("RPT_ECONOMY_CPI#2026-08-01");
        assertThat(newest.title()).isEqualTo("CPI：2026年08月份 同比 0.8%");
        assertThat(newest.summary())
                .contains("全国同比 0.8%")
                .contains("环比 0.4%")
                .contains("城市同比 0.8%");
        // REPORT_DATE 墙钟（数据期月首日北京时间）→ UTC；数据期而非发布时刻（类注释裁量）
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
        assertThat(newest.author()).isEqualTo("东方财富数据中心");
        // cursorType=NONE：不产出游标（重复轮由唯一索引幂等吸收）
        assertThat(newest.cursorValue()).isNull();
        // 序列行无直链
        assertThat(newest.url()).isNull();
    }

    @Test
    void parseReport_sourceSideError_throwsFeedFetchException() {
        // 9501 类源侧报错（如排序列漂移）：success=false → 明确异常（不吞，走 last_error 诊断面）
        assertThatThrownBy(
                        () ->
                                adapter.parseReport(
                                        "{\"success\":false,\"code\":9501,\"message\":\"REPORT_DATE排序列不存在\",\"result\":null}",
                                        macroSource(),
                                        new EastmoneyMacroAdapter.MacroReport(
                                                "RPT_ECONOMY_CPI", "CPI")))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("9501");
    }

    @Test
    void fetch_allReportsFail_throwsRoundFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                EastmoneyMacroAdapter.reportUrl(
                                        new EastmoneyMacroAdapter.MacroReport(
                                                "RPT_ECONOMY_CPI", "CPI"))))
                .andRespond(
                        withSuccess(
                                "{\"success\":false,\"code\":9501,\"message\":\"x\",\"result\":null}",
                                MediaType.APPLICATION_JSON));
        EastmoneyMacroAdapter httpAdapter = new EastmoneyMacroAdapter(builder.build());

        // 全字典失败 → 轮失败（调度退避接管；单报表失败续跑路径待字典扩行后补测）
        assertThatThrownBy(() -> httpAdapter.fetch(macroSource(), FetchContext.firstPage(null)))
                .isInstanceOf(FeedFetchException.class)
                .hasMessageContaining("全部报表取数失败");
        server.verify();
    }

    @Test
    void reportUrl_carriesDescendingSortParameters() {
        // 默认序最旧在前（实测），显式 REPORT_DATE 降序是取到最新窗口的前提——参数回归
        String url =
                EastmoneyMacroAdapter.reportUrl(
                        new EastmoneyMacroAdapter.MacroReport("RPT_ECONOMY_CPI", "CPI"));

        assertThat(url)
                .contains("reportName=RPT_ECONOMY_CPI")
                .contains("sortColumns=REPORT_DATE")
                .contains("sortTypes=-1")
                .contains("pageNumber=1");
    }

    @Test
    void fetch_successPath_itemsReturnedNotTruncated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(
                        requestTo(
                                EastmoneyMacroAdapter.reportUrl(
                                        new EastmoneyMacroAdapter.MacroReport(
                                                "RPT_ECONOMY_CPI", "CPI"))))
                .andRespond(withSuccess(fixture(), MediaType.APPLICATION_JSON));
        EastmoneyMacroAdapter httpAdapter = new EastmoneyMacroAdapter(builder.build());

        var result = httpAdapter.fetch(macroSource(), FetchContext.firstPage(null));

        assertThat(result.items()).hasSize(3);
        assertThat(result.truncated()).isFalse();
        server.verify();
    }
}
