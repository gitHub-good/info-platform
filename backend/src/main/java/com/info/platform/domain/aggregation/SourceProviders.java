package com.info.platform.domain.aggregation;

import java.util.List;
import java.util.Map;

/**
 * 每源可用 provider 注册表（ADR-0033 降级链模型的代码事实，成员以 {@link SourceProvider} 枚举维护）。
 *
 * <p>「该源当前接入的真实 provider 有哪些、默认主源是谁」是代码能力而非配置——新增 provider 必须先写接入代码， 故注册表内置于域层供三方共用：① 配置校验器（chain
 * 成员 ∈ 注册表）；② 配置页下拉与卡片回显（可用 provider 清单）； ③ 引擎缺省兜底（注册表全链即 auto 语义，首元素 = 默认主源）。
 *
 * <p>单 provider 源（注册表长度 1）页面呈现「暂无备选源」；接入第二 provider 后在 {@link #PROVIDERS} 补行即自动
 * 进入链模型——代码先行的能力开关，配置只在能力范围内选择与排序。
 */
public final class SourceProviders {

    /** 各源可用 provider（有序：首元素 = 默认主源 = auto 降级链的起点；多元素按默认降级顺序排列）。 */
    private static final Map<SourceCode, List<SourceProvider>> PROVIDERS =
            Map.of(
                    SourceCode.QUOTE, List.of(SourceProvider.EASTMONEY, SourceProvider.TENCENT),
                    SourceCode.VALUATION, List.of(SourceProvider.EASTMONEY, SourceProvider.TENCENT),
                    SourceCode.FINANCE, List.of(SourceProvider.EASTMONEY, SourceProvider.SINA),
                    SourceCode.ANNOUNCE, List.of(SourceProvider.EASTMONEY),
                    SourceCode.NEWS, List.of(SourceProvider.SINA),
                    SourceCode.POLICY, List.of(SourceProvider.GOV),
                    SourceCode.EVENT, List.of(SourceProvider.LOCAL));

    /**
     * A 股列表桶（{@code subject.sync}）可用 provider：列表源无 {@code SourceCode} 键，独立声明（ADR-0030 选型： 新浪仅 A
     * 股节点，港股/指数桶恒东财）。
     */
    public static final List<SourceProvider> A_SHARE_LIST_PROVIDERS =
            List.of(SourceProvider.EASTMONEY, SourceProvider.SINA);

    private SourceProviders() {}

    /** 该源可用 provider 清单（不可变，含序）；未知源返回空清单（校验器据此拒绝任何 chain 成员）。 */
    public static List<SourceProvider> providers(SourceCode code) {
        return PROVIDERS.getOrDefault(code, List.of());
    }

    /** 该源可用 provider 的契约 code 清单（API/校验的字符串边界用，与 {@link #providers} 同序）。 */
    public static List<String> providerCodes(SourceCode code) {
        return providers(code).stream().map(SourceProvider::code).toList();
    }
}
