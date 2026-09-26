package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.application.valuation.ValuationConfigValidator;
import com.info.platform.domain.valuation.ValuationParams;
import org.junit.jupiter.api.Test;

/**
 * ScoreWeightRuntimeConfigSeeder 单测（T172，方案 §4.3）：score.weight 单键种子结构 = ValuationParams.defaults()
 * 单一事实源（13 参数逐项锁定）；种子值必须通过本批校验器（种子自合法性——否则新装首写前读侧即取到非法文档）；seed-if-absent 幂等由 RuntimeConfigService
 * 通用机制保证（已有测试覆盖）。
 */
class ScoreWeightRuntimeConfigSeederTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfigSeed seed() throws Exception {
        return new ScoreWeightRuntimeConfigSeeder(objectMapper).seeds().get(0);
    }

    @Test
    void seeds_scoreWeightKey_withDefaults() throws Exception {
        RuntimeConfigSeed seed = seed();

        assertThat(seed.configKey()).isEqualTo("score.weight");
        assertThat(seed.description()).contains("权重").contains("热生效");
        JsonNode doc = objectMapper.readTree(seed.json());
        ValuationParams defaults = ValuationParams.defaults();
        assertThat(doc.get("wCatalyst").asDouble()).isEqualTo(defaults.wCatalyst());
        assertThat(doc.get("wConduction").asDouble()).isEqualTo(defaults.wConduction());
        assertThat(doc.get("wFundamental").asDouble()).isEqualTo(defaults.wFundamental());
        assertThat(doc.get("wRisk").asDouble()).isEqualTo(defaults.wRisk());
        assertThat(doc.get("wValuation").asDouble()).isEqualTo(defaults.wValuation());
        assertThat(doc.get("catalystWindowDays").asInt()).isEqualTo(defaults.catalystWindowDays());
        assertThat(doc.get("assocWindowDays").asInt()).isEqualTo(defaults.assocWindowDays());
        assertThat(doc.get("halfLifeDays").asDouble()).isEqualTo(defaults.halfLifeDays());
        assertThat(doc.get("k1Saturation").asDouble()).isEqualTo(defaults.k1Saturation());
        assertThat(doc.get("k3Saturation").asDouble()).isEqualTo(defaults.k3Saturation());
        assertThat(doc.get("btCatalystMin").asInt()).isEqualTo(defaults.btCatalystMin());
        assertThat(doc.get("btConductionMin").asInt()).isEqualTo(defaults.btConductionMin());
        assertThat(doc.get("btRiskMin").asInt()).isEqualTo(defaults.btRiskMin());
        // basis 为读时代码派生字段，不入种子文档（避免双事实源漂移）
        assertThat(doc.has("basis")).isFalse();
    }

    @Test
    void seeds_singleKey_validAgainstOwnValidator() throws Exception {
        assertThat(new ScoreWeightRuntimeConfigSeeder(objectMapper).seeds()).hasSize(1);
        JsonNode doc = objectMapper.readTree(seed().json());
        ValuationConfigValidator validator = new ValuationConfigValidator();
        assertThatCode(() -> validator.validate("score.weight", doc)).doesNotThrowAnyException();
    }
}
