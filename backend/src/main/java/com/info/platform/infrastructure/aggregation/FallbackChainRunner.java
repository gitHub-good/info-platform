package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 降级链取数引擎（ADR-0033）：多 provider 源按 {@code fallbackChain} 顺序依次尝试。
 *
 * <p>单 provider 源经 {@link #chainOf} 退化为单元素链，行为与旧版直呼一致（零降级尝试）。每级失败/空响应记 WARN 留痕带 provider
 * 名与链位；命中即返，{@code RawFetch.source} 标注实际命中 provider（「主源」或「主源→X备选」， 连通性测试与健康徽章可区分兜底轮）。全链失败抛末级异常（前级挂
 * {@code suppressed} 留诊断链，沿既有弹性语义降级）； 全链空响应返回 empty（→ MISSING，不阻断聚合）。
 */
final class FallbackChainRunner {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainRunner.class);

    /** provider 展示名（{@code RawFetch.source} 标注与日志留痕共用；前端另有同名映射）。 */
    static String providerLabel(SourceProvider provider) {
        return provider.displayName();
    }

    /** 单 provider 取数（label 为该链位标注，由 {@link #labelOf} 生成）。 */
    @FunctionalInterface
    interface ProviderFetch {

        /** 返回 empty 表示该 provider 当日无数据（→ 尝试下一级 / 全空 MISSING）；抛异常表示该级取数失败。 */
        Optional<RawFetch> fetch(SourceProvider provider, String label) throws Exception;
    }

    /** 按链依次取数（每级 WARN 留痕带 provider 名；语义细则见类 Javadoc）。 */
    static Optional<RawFetch> fetch(
            String sourceNoun, List<SourceProvider> chain, ProviderFetch fetcher) throws Exception {
        RuntimeException lastFailure = null;
        for (int index = 0; index < chain.size(); index++) {
            SourceProvider provider = chain.get(index);
            String label = labelOf(sourceNoun, chain, index);
            try {
                Optional<RawFetch> result = fetcher.fetch(provider, label);
                if (result.isPresent()) {
                    return result;
                }
                log.warn(
                        "{}取数空响应（provider={}，链位 {}/{}），按降级链尝试下一备选",
                        providerLabel(provider),
                        provider.code(),
                        index + 1,
                        chain.size());
            } catch (RuntimeException failure) {
                if (lastFailure != null) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
                log.warn(
                        "{}取数失败（provider={}，链位 {}/{}）：{} → 按降级链尝试下一备选",
                        providerLabel(provider),
                        provider.code(),
                        index + 1,
                        chain.size(),
                        failure.toString());
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        return Optional.empty();
    }

    /**
     * 链位来源标注：链位 0 = 主源（「{provider}{业务名}」，与旧版 sourceLabel / 腾讯单源标注同文案）； 链位 &gt;0 =
     * 「{主源}→{provider}备选」（与 ADR-0031「→腾讯备选」同款，健康徽章可区分兜底轮）。
     */
    static String labelOf(String sourceNoun, List<SourceProvider> chain, int index) {
        String primary = providerLabel(chain.get(0)) + sourceNoun;
        return index == 0 ? primary : primary + "→" + providerLabel(chain.get(index)) + "备选";
    }

    private FallbackChainRunner() {}
}
