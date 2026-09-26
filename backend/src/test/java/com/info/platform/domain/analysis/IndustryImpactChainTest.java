package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 行业影响链实体单测（M17 T144，V28 {@code industry_impact_chain}）：申万白名单把守（容器/未知行业拒）、 方向/逻辑链必填、缓存态枚举合法值。 */
class IndustryImpactChainTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    @Test
    @DisplayName("建链成功：AUTO 缓存态 + 依据回溯 JSON")
    void create_autoChain_succeeds() {
        IndustryImpactChain chain =
                IndustryImpactChain.create(
                        901L,
                        "银行",
                        Direction.BULLISH,
                        "流动性宽松降低银行负债成本，信贷投放预期改善",
                        "{\"newsId\":100,\"signalNewsIds\":[100]}",
                        "POLICY_MONETARY",
                        ImpactCacheState.AUTO,
                        NOW);

        assertThat(chain.getIndustry()).isEqualTo("银行");
        assertThat(chain.getDirection()).isEqualTo(Direction.BULLISH);
        assertThat(chain.getTemplateKey()).isEqualTo("POLICY_MONETARY");
        assertThat(chain.getCacheState()).isEqualTo(ImpactCacheState.AUTO);
        assertThat(chain.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("白名单把守：容器行业（宏观）与未知行业拒")
    void create_containerOrUnknownIndustry_rejected() {
        assertThatThrownBy(
                        () ->
                                IndustryImpactChain.create(
                                        901L,
                                        "宏观",
                                        Direction.BULLISH,
                                        "逻辑链",
                                        "{}",
                                        "POLICY_MONETARY",
                                        ImpactCacheState.AUTO,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("申万");

        assertThatThrownBy(
                        () ->
                                IndustryImpactChain.create(
                                        901L,
                                        "不存在的行业",
                                        Direction.BULLISH,
                                        "逻辑链",
                                        "{}",
                                        "POLICY_MONETARY",
                                        ImpactCacheState.AUTO,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("必填把守：方向/逻辑链/模板键/缓存态缺失拒")
    void create_missingRequiredFields_rejected() {
        assertThatThrownBy(
                        () ->
                                IndustryImpactChain.create(
                                        901L,
                                        "银行",
                                        null,
                                        "逻辑链",
                                        "{}",
                                        "POLICY_MONETARY",
                                        ImpactCacheState.AUTO,
                                        NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                IndustryImpactChain.create(
                                        901L,
                                        "银行",
                                        Direction.BULLISH,
                                        " ",
                                        "{}",
                                        "POLICY_MONETARY",
                                        ImpactCacheState.AUTO,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                IndustryImpactChain.create(
                                        901L,
                                        "银行",
                                        Direction.BULLISH,
                                        "逻辑链",
                                        "{}",
                                        null,
                                        ImpactCacheState.AUTO,
                                        NOW))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("持久化重建：全字段回读等值")
    void reconstruct_allFieldsRoundTrip() {
        IndustryImpactChain chain =
                IndustryImpactChain.reconstruct(
                        1L,
                        901L,
                        "房地产",
                        Direction.BULLISH,
                        "资金面宽松支撑按揭利率下行预期",
                        "{\"newsId\":100}",
                        "POLICY_MONETARY",
                        ImpactCacheState.ON_DEMAND,
                        NOW,
                        NOW);

        assertThat(chain.getId()).isEqualTo(1L);
        assertThat(chain.getCacheState()).isEqualTo(ImpactCacheState.ON_DEMAND);
        assertThat(chain.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("缓存态枚举面：AUTO / ON_DEMAND 两值")
    void cacheState_values() {
        assertThat(List.of(ImpactCacheState.values()))
                .extracting(Enum::name)
                .containsExactly("AUTO", "ON_DEMAND");
    }
}
