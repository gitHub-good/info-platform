package com.info.platform.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 展示字段解析兜底回归（2026-09-22 沃森生物简报 ai_brief id=13 实测）：v1.0 模板未强制 {@code keyEvents[].event} 与 {@code
 * facts[].claim} 必填，模型把内容全写进 reason、event/claim 输出 null → 前端关键事件区无标题、事实表无陈述。
 *
 * <p>兜底写在解析器后处理：生成路径（parse→writeJson 落库归一化）与读取路径（getBrief 重解析存量 content）双路径生效——存量简报（id=13
 * 类）与新简报展示均不空。
 */
class BriefContentParserDisplayFallbackTest {

    private final BriefContentParser parser = new BriefContentParser(new ObjectMapper());

    @Test
    void parse_nullEventTitle_fallsBackToReasonFirstSentenceTruncatedTo20() {
        // Arrange：id=13 实测形状——event null、内容全在 reason
        String raw =
                "{\"summary\":\"s\",\"facts\":[],\"keyEvents\":[{\"event\":null,"
                        + "\"impact\":\"利好\",\"reason\":\"子公司获得药品注册证书意味着产品取得上市许可资格，"
                        + "为公司业务面构成正向事件\",\"sourceUrl\":\"https://example.com/a\"}],"
                        + "\"disclaimer\":\"d\"}";

        // Act
        var parsed = parser.parse(raw);

        // Assert：event 兜底为 reason 首句（≤20 字）；impact/reason/sourceUrl 原样保留
        assertThat(parsed).as("null event 不应导致整份解析失败").isPresent();
        var event = parsed.orElseThrow().keyEvents().get(0);
        assertThat(event.event()).isNotBlank();
        assertThat(event.event().length()).isLessThanOrEqualTo(20);
        assertThat(event.event()).startsWith("子公司获得药品注册证书");
        assertThat(event.impact()).isEqualTo("利好");
        assertThat(event.reason()).contains("上市许可资格");
        assertThat(event.sourceUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void parse_eventAndReasonBothBlank_fallsBackToGenericTitle() {
        // Arrange：event 与 reason 均空——无可合成素材，兜底泛化标题保证不空
        String raw =
                "{\"summary\":\"s\",\"facts\":[],\"keyEvents\":[{\"event\":\"\",\"impact\":\"中性\","
                        + "\"reason\":null}],\"disclaimer\":\"d\"}";

        // Act
        var parsed = parser.parse(raw);

        // Assert
        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().keyEvents().get(0).event()).isEqualTo("关键事件");
    }

    @Test
    void parse_presentEventTitle_keptUntouched() {
        // Arrange：event 已有值（v1.1 模板产物）
        String raw =
                "{\"summary\":\"s\",\"facts\":[],\"keyEvents\":[{\"event\":\"定增获受理\","
                        + "\"impact\":\"中性\",\"reason\":\"r\"}],\"disclaimer\":\"d\"}";

        // Act + Assert：非空 event 不改写
        assertThat(parser.parse(raw).orElseThrow().keyEvents().get(0).event()).isEqualTo("定增获受理");
    }

    @Test
    void parse_nullClaim_fallsBackToMetricLabel() {
        // Arrange：id=13 实测形状——claim null、metric/value/source 有值
        String raw =
                "{\"summary\":\"s\",\"keyEvents\":[],\"facts\":[{\"claim\":null,\"metric\":\"price\","
                        + "\"value\":13.58,\"source\":\"用户提供聚合行情数据\",\"sourceUrl\":\"\"}],"
                        + "\"disclaimer\":\"d\"}";

        // Act
        var parsed = parser.parse(raw);

        // Assert：claim 兜底「{metric} 数值」；metric/value/source 原样保留（source 供幻觉回查，净化在前端）
        assertThat(parsed).isPresent();
        var fact = parsed.orElseThrow().facts().get(0);
        assertThat(fact.claim()).isEqualTo("price 数值");
        assertThat(fact.metric()).isEqualTo("price");
        assertThat(fact.value()).isEqualTo(13.58);
        assertThat(fact.source()).isEqualTo("用户提供聚合行情数据");
    }

    @Test
    void parse_claimAndMetricBothBlank_claimStaysNull() {
        // Arrange：claim 与 metric 均空——无素材可合成，保持 null（前端显示 —）
        String raw =
                "{\"summary\":\"s\",\"keyEvents\":[],\"facts\":[{\"claim\":null,\"metric\":null,"
                        + "\"value\":1.0,\"source\":\"FINANCE\",\"sourceUrl\":null}],"
                        + "\"disclaimer\":\"d\"}";

        // Act + Assert
        assertThat(parser.parse(raw).orElseThrow().facts().get(0).claim()).isNull();
    }

    @Test
    void parse_presentClaim_keptUntouched() {
        // Arrange
        String raw =
                "{\"summary\":\"s\",\"keyEvents\":[],\"facts\":[{\"claim\":\"归母净利润8.34亿元\","
                        + "\"metric\":\"net_profit\",\"value\":8.34,\"source\":\"FINANCE\"}],"
                        + "\"disclaimer\":\"d\"}";

        // Act + Assert：非空 claim 不改写
        assertThat(parser.parse(raw).orElseThrow().facts().get(0).claim()).isEqualTo("归母净利润8.34亿元");
    }
}
