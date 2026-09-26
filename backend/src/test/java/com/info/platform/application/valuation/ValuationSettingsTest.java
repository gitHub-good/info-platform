package com.info.platform.application.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ValuationSettings 单测（T170，方案 §4.3 + ADR-0017 快照热读）：score.weight 键缺失回落代码缺省、
 * 合法覆写全量采信、非法值（越界/全零权重/类型损坏）字段级回退——运行时兜底（写路径 30087 归 T172 校验器）。
 */
class ValuationSettingsTest {

    private RuntimeConfigService configService;
    private ValuationSettings settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        configService = mock(RuntimeConfigService.class);
        settings = new ValuationSettings(configService, objectMapper);
    }

    private void stubDoc(String json) {
        when(configService.read(anyString()))
                .thenReturn(
                        Optional.of(
                                new RuntimeConfigEntry("score.weight", json, null, null, null)));
    }

    @Test
    void keyMissing_fallsBackToDefaults() {
        when(configService.read(anyString())).thenReturn(Optional.empty());

        assertThat(settings.params()).isEqualTo(ValuationParamsDefaults.expectedDefaults());
    }

    @Test
    void validOverrides_adopted() {
        stubDoc(
                "{\"wCatalyst\":0.5,\"wConduction\":0.1,\"wFundamental\":0.2,\"wRisk\":0.2,"
                        + "\"wValuation\":0.0,\"catalystWindowDays\":15,\"assocWindowDays\":45,"
                        + "\"halfLifeDays\":7.0,\"k1Saturation\":4.0,\"k3Saturation\":2.0,"
                        + "\"btCatalystMin\":65,\"btConductionMin\":40,\"btRiskMin\":75}");

        assertThat(settings.params().wCatalyst()).isEqualTo(0.5);
        assertThat(settings.params().assocWindowDays()).isEqualTo(45);
        assertThat(settings.params().halfLifeDays()).isEqualTo(7.0);
        assertThat(settings.params().k1Saturation()).isEqualTo(4.0);
        assertThat(settings.params().btRiskMin()).isEqualTo(75);
    }

    @Test
    void allZeroWeights_wholeParamsFallBackToDefaults() {
        // Σw=0 不可用（除零）→ 整体回落缺省（写路径拦截之外的第二道防线）
        stubDoc(
                "{\"wCatalyst\":0,\"wConduction\":0,\"wFundamental\":0,\"wRisk\":0,"
                        + "\"wValuation\":0}");

        assertThat(settings.params()).isEqualTo(ValuationParamsDefaults.expectedDefaults());
    }

    @Test
    void outOfRangeFields_fallBackPerField() {
        stubDoc(
                "{\"wCatalyst\":1.5,\"catalystWindowDays\":99,\"assocWindowDays\":5,"
                        + "\"halfLifeDays\":0.5,\"k1Saturation\":50,\"btCatalystMin\":120}");

        assertThat(settings.params().wCatalyst()).isEqualTo(0.40); // >1 回落
        assertThat(settings.params().catalystWindowDays()).isEqualTo(10); // 越界回落
        assertThat(settings.params().assocWindowDays()).isEqualTo(30); // <10 回落
        assertThat(settings.params().halfLifeDays()).isEqualTo(5.0); // <1 回落
        assertThat(settings.params().k1Saturation()).isEqualTo(3.0); // >10 回落
        assertThat(settings.params().btCatalystMin()).isEqualTo(60); // >100 回落
    }

    @Test
    void corruptedDocument_fallsBackToDefaults() {
        stubDoc("not-a-json");

        assertThat(settings.params()).isEqualTo(ValuationParamsDefaults.expectedDefaults());
    }

    /** 测试侧缺省镜像（避免测试与实现共用同一常量导致的自证）。 */
    private static final class ValuationParamsDefaults {
        static com.info.platform.domain.valuation.ValuationParams expectedDefaults() {
            return new com.info.platform.domain.valuation.ValuationParams(
                    0.40, 0.20, 0.20, 0.20, 0.00, 10, 30, 5.0, 3.0, 1.5, 60, 50, 80);
        }
    }
}
