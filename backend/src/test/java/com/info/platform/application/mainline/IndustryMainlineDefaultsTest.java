package com.info.platform.application.mainline;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.common.RuntimeConfigSeed;
import org.junit.jupiter.api.Test;

/**
 * IndustryMainlineDefaults 种子单测（M27 T243，方案 §4.3.3）：两键 seed-if-absent 文档 = §3.4/§3.5 冻结值（与 Settings
 * 缺省、 校验器合法基线三方一致——种子漂移由此暴露）。
 */
class IndustryMainlineDefaultsTest {

    @Test
    void seeds_carriesBothKeysWithFrozenDefaults() throws Exception {
        var seeds = new IndustryMainlineDefaults().seeds();

        assertThat(seeds).hasSize(2);
        RuntimeConfigSeed mainline = seeds.get(0);
        RuntimeConfigSeed leader = seeds.get(1);
        assertThat(mainline.configKey()).isEqualTo("industry.mainline");
        assertThat(leader.configKey()).isEqualTo("industry.leader");
        // 种子 JSON 与校验器合法基线一致（可直过 validate）
        new IndustryMainlineConfigValidator()
                .validate(
                        "industry.mainline",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(mainline.json()));
        new IndustryMainlineConfigValidator()
                .validate(
                        "industry.leader",
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(leader.json()));
        assertThat(mainline.json()).contains("\"wp\":0.40").contains("\"topN\":5");
        assertThat(leader.json()).contains("\"wa\":0.50").contains("\"mentionDays\":7");
    }
}
