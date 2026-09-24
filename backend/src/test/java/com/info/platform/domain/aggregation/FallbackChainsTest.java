package com.info.platform.domain.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FallbackChains} 单测（ADR-0033 降级链解析）：DB 链优先 / 空链仅主源 / 旧单值开关折算（auto→全链、单值→单元素链） / 全缺省注册表全链兜底
 * / 非法链与非法旧值回落全链 / 合法性判断（成员越界、重复、大小写）。
 */
class FallbackChainsTest {

    private static final List<SourceProvider> REGISTRY =
            List.of(SourceProvider.EASTMONEY, SourceProvider.TENCENT);

    @Test
    void resolve_explicitChain_winsAsIs() {
        assertThat(FallbackChains.resolve(List.of("tencent", "eastmoney"), "eastmoney", REGISTRY))
                .containsExactly(SourceProvider.TENCENT, SourceProvider.EASTMONEY);
    }

    @Test
    void resolve_explicitEmptyChain_primaryOnly() {
        // 页面清空备选（仅主源）：主源回落注册表首元素（默认主源）
        assertThat(FallbackChains.resolve(List.of(), null, REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY);
    }

    @Test
    void resolve_missingChain_legacyAuto_foldsToFullRegistry() {
        assertThat(FallbackChains.resolve(null, "auto", REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_missingChain_legacySingleValue_foldsToSingleElementChain() {
        // 旧强制单源语义：tencent → [tencent]
        assertThat(FallbackChains.resolve(null, "tencent", REGISTRY))
                .containsExactly(SourceProvider.TENCENT);
    }

    @Test
    void resolve_missingChain_blankLegacy_fallsBackToFullRegistry() {
        assertThat(FallbackChains.resolve(null, " ", REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_nothingConfigured_codeDefaultChainFallsBack() {
        // 兜底语义（用户拍板）：DB 无链无旧键 → 代码内置默认链（= 注册表全链）
        assertThat(FallbackChains.resolve(null, null, REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_invalidChainMember_fallsBackToFullRegistry() {
        assertThat(FallbackChains.resolve(List.of("eastmoney", "sina"), null, REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_duplicateMembers_fallsBackToFullRegistry() {
        assertThat(FallbackChains.resolve(List.of("eastmoney", "eastmoney"), null, REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_invalidLegacyValue_fallsBackToFullRegistry() {
        assertThat(FallbackChains.resolve(null, "not-a-mode", REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void resolve_legacyValueCaseInsensitive_auto() {
        assertThat(FallbackChains.resolve(null, "AUTO", REGISTRY))
                .containsExactly(SourceProvider.EASTMONEY, SourceProvider.TENCENT);
    }

    @Test
    void isValidChain_acceptsMembersInRegistryWithoutDuplicates() {
        assertThat(FallbackChains.isValidChain(List.of("tencent", "eastmoney"), REGISTRY)).isTrue();
        // 空链合法（仅主源）；null 视为未配置
        assertThat(FallbackChains.isValidChain(List.of(), REGISTRY)).isTrue();
        assertThat(FallbackChains.isValidChain(null, REGISTRY)).isTrue();
    }

    @Test
    void isValidChain_rejectsUnknownMemberAndDuplicate() {
        assertThat(FallbackChains.isValidChain(List.of("eastmoney", "sina"), REGISTRY)).isFalse();
        assertThat(FallbackChains.isValidChain(List.of("eastmoney", "eastmoney"), REGISTRY))
                .isFalse();
    }

    @Test
    void isValidLegacy_acceptsBlankAutoAndRegistryMembers() {
        assertThat(FallbackChains.isValidLegacy(null, REGISTRY)).isTrue();
        assertThat(FallbackChains.isValidLegacy("auto", REGISTRY)).isTrue();
        assertThat(FallbackChains.isValidLegacy(" Tencent ", REGISTRY)).isTrue();
        assertThat(FallbackChains.isValidLegacy("sina", REGISTRY)).isFalse();
    }
}
