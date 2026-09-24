package com.info.platform.domain.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 降级链解析（ADR-0033）：DB 配置链优先，代码内置默认链只做兜底；旧单值开关读取兼容折算。 内部一律持 {@link SourceProvider} 枚举，契约边界（JSON/存储）由
 * {@link SourceProvider#code()} 与 {@link SourceProvider#fromCode(String)} 转换。
 *
 * <p>解析优先级（读取侧，每次取数现算——热生效）：
 *
 * <ol>
 *   <li><b>显式 {@code fallbackChain}</b>：非空链按序使用（首元素 = 主源）；空链 = 仅主源（主源回落注册表首元素）； 非法链（成员不在注册表 / 重复）→
 *       兜底注册表全链（调用方负责 WARN 留痕后置 null）
 *   <li><b>旧开关折算</b>（{@code backupSource} / {@code aShareSource}，逐步淘汰的兼容口径）：{@code auto}（或空） →
 *       注册表全链；单值（如 {@code tencent}）→ 单元素链（强制单源语义）；非法旧值 → 全链兜底
 *   <li><b>兜底</b>：链与旧键均缺失 → 注册表全链（即代码内置默认链，首元素 = 默认主源）
 * </ol>
 *
 * <p>纯函数无副作用：非法值的 WARN 由基础设施消费点负责（域层不记日志）。
 */
public final class FallbackChains {

    private static final String AUTO = "auto";

    private FallbackChains() {}

    /**
     * 解析有效降级链。
     *
     * @param rawChain DB 文档的 {@code fallbackChain} 原始值（null = 未配置；空清单 = 仅主源）
     * @param legacyValue 旧单值开关原始值（{@code backupSource} / {@code aShareSource}；null/空 = 缺省）
     * @param registry 该源可用 provider（有序，首元素 = 默认主源）
     * @return 有效降级链（不可变，至少含主源一个元素）
     */
    public static List<SourceProvider> resolve(
            List<String> rawChain, String legacyValue, List<SourceProvider> registry) {
        if (rawChain != null) {
            if (rawChain.isEmpty()) {
                return List.of(registry.get(0));
            }
            List<SourceProvider> parsed = parseChain(rawChain, registry);
            return parsed != null ? parsed : List.copyOf(registry);
        }
        if (legacyValue != null && !legacyValue.isBlank()) {
            String normalized = legacyValue.trim().toLowerCase(Locale.ROOT);
            if (!AUTO.equals(normalized)) {
                SourceProvider legacy = SourceProvider.fromCode(normalized);
                if (legacy != null && registry.contains(legacy)) {
                    return List.of(legacy);
                }
            }
        }
        return List.copyOf(registry);
    }

    /** chain 合法性：成员 ∈ 注册表且无重复（空链合法 = 仅主源；null 视为未配置不参与判断）。 */
    public static boolean isValidChain(List<String> chain, List<SourceProvider> registry) {
        if (chain == null || chain.isEmpty()) {
            return true;
        }
        return parseChain(chain, registry) != null;
    }

    /** 旧单值开关合法性：空白（= 缺省 auto）/{@code auto}/注册表成员（大小写不敏感）。 */
    public static boolean isValidLegacy(String raw, List<SourceProvider> registry) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (AUTO.equals(normalized)) {
            return true;
        }
        SourceProvider provider = SourceProvider.fromCode(normalized);
        return provider != null && registry.contains(provider);
    }

    /** 契约链 → 枚举链（成员逐个 {@link SourceProvider#fromCode} 且 ∈ 注册表、无重复；任一不满足返回 null）。 */
    private static List<SourceProvider> parseChain(
            List<String> chain, List<SourceProvider> registry) {
        List<SourceProvider> parsed = new ArrayList<>(chain.size());
        for (String raw : chain) {
            SourceProvider provider = SourceProvider.fromCode(raw);
            if (provider == null || !registry.contains(provider) || parsed.contains(provider)) {
                return null;
            }
            parsed.add(provider);
        }
        return List.copyOf(parsed);
    }
}
