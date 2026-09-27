package com.info.platform.domain.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ValuationParams 单测（T170，方案 §4.2/§4.3 冻结值）：缺省参数五维权重/窗口/半衰期/K/三阈值逐值断言 + basis 指纹串逐字对齐（复算审计锚，§3.4）。
 */
class ValuationParamsTest {

    @Test
    void defaults_carryFrozenValues() {
        ValuationParams params = ValuationParams.defaults();

        assertThat(params.wCatalyst()).isEqualTo(0.40);
        assertThat(params.wConduction()).isEqualTo(0.20);
        assertThat(params.wFundamental()).isEqualTo(0.20);
        assertThat(params.wRisk()).isEqualTo(0.20);
        assertThat(params.wValuation()).isEqualTo(0.00);
        assertThat(params.catalystWindowDays()).isEqualTo(10);
        assertThat(params.assocWindowDays()).isEqualTo(30);
        assertThat(params.halfLifeDays()).isEqualTo(5.0);
        assertThat(params.k1Saturation()).isEqualTo(3.0);
        assertThat(params.k3Saturation()).isEqualTo(1.5);
        // btCatalystMin 60→20：M21 T180 校准（OBS-M20-2，V31 迁移守卫同步存量 DB 行）
        assertThat(params.btCatalystMin()).isEqualTo(20);
        assertThat(params.btConductionMin()).isEqualTo(50);
        assertThat(params.btRiskMin()).isEqualTo(80);
    }

    @Test
    void basis_matchesDesignFormatVerbatim() {
        // §4.3 键文档示例逐字对齐：权重两位小数 | win 整数 | hl/k 原样 double | bt 整数（catalyst|conduction|risk）
        assertThat(ValuationParams.defaults().basis())
                .isEqualTo(
                        "vs-v1:w=0.40|0.20|0.20|0.20|0.00;win=10|30;hl=5.0;k=3.0|1.5;bt=20|50|80");
    }

    @Test
    void basis_changesWithEveryParamSlice() {
        ValuationParams base = ValuationParams.defaults();

        assertThat(base.withWCatalyst(0.5).basis()).isNotEqualTo(base.basis());
        assertThat(base.withCatalystWindowDays(15).basis()).isNotEqualTo(base.basis());
        assertThat(base.withAssocWindowDays(45).basis()).isNotEqualTo(base.basis());
        assertThat(base.withHalfLifeDays(7.0).basis()).isNotEqualTo(base.basis());
        assertThat(base.withK1Saturation(4.0).basis()).isNotEqualTo(base.basis());
        assertThat(base.withK3Saturation(2.0).basis()).isNotEqualTo(base.basis());
        assertThat(base.withBtCatalystMin(65).basis()).isNotEqualTo(base.basis());
    }

    @Test
    void weightSum_zeroWeightsIsInvalid() {
        // 全零权重非法（除零防线；写路径 30087 拦截，读侧 ValuationSettings 回落缺省）
        assertThat(ValuationParams.defaults().weightSum()).isEqualTo(1.0);
        assertThat(
                        new ValuationParams(0, 0, 0, 0, 0, 10, 30, 5.0, 3.0, 1.5, 60, 50, 80)
                                .weightSum())
                .isZero();
    }
}
