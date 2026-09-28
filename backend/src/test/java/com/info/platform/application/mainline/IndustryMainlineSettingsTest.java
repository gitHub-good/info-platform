package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * IndustryMainlineSettings 读侧回退单测（M27 T243，方案 §4.3.3）：键缺失全缺省 / 字段级非法回退 / 合法值采信——计算永不因配置损坏而炸
 * （ValuationConfigSettings 同款防御）。
 */
class IndustryMainlineSettingsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode doc(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void parseMainline_nullDoc_allDefaults() {
        var params = IndustryMainlineSettings.parseMainline(null);

        assertThat(params.wp()).isEqualTo(0.40);
        assertThat(params.wh()).isEqualTo(0.35);
        assertThat(params.we()).isEqualTo(0.25);
        assertThat(params.topN()).isEqualTo(5);
        assertThat(params.persistMinDays()).isEqualTo(2);
        assertThat(params.persistWindowDays()).isEqualTo(5);
        assertThat(params.topThirdRank()).isEqualTo(10);
        assertThat(params.divergenceHeatRank()).isEqualTo(13);
    }

    @Test
    void parseMainline_illegalFields_fallBackPerField() throws Exception {
        var params =
                IndustryMainlineSettings.parseMainline(
                        doc(
                                "{\"wp\":\"x\",\"wh\":0.6,\"topN\":3.5,"
                                        + "\"persistMinDays\":\"2\"}"));

        assertThat(params.wp()).isEqualTo(0.40); // 字符串数值回退
        assertThat(params.wh()).isEqualTo(0.6);
        assertThat(params.topN()).isEqualTo(5); // 非整数回退
        assertThat(params.persistMinDays()).isEqualTo(2);
    }

    @Test
    void parseLeader_defaultsAndOverride() throws Exception {
        assertThat(IndustryMainlineSettings.parseLeader(null).wa()).isEqualTo(0.50);
        var params =
                IndustryMainlineSettings.parseLeader(
                        doc("{\"wa\":0.6,\"wv\":0.3,\"wq\":0.1,\"topN\":5}"));
        assertThat(params.wa()).isEqualTo(0.6);
        assertThat(params.topN()).isEqualTo(5);
        assertThat(params.mentionDays()).isEqualTo(7);
    }
}
