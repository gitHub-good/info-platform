package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.HkusQuoteSource;
import com.info.platform.domain.aggregation.SubjectCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link HkusQuoteSource} 端口的腾讯主链实现（M29 T252，ADR-0064 裁决 1）：纯委托既有 {@link TencentQuoteClient} （符号换算含
 * US 大写分支 + 50 只/块自动分块 + GBK 解码 + f 键映射零改动复用）——备选源对快照层完全透明。
 *
 * <p>东财 push2 港美股行情<b>不入备链</b>（IP 封禁期 + US 字段口径未实证，ADR-0064 裁决 1 否决项）——解封复核后升链尾：
 *
 * <pre>{@code
 * // TODO(M29-R2, Spike-E §7 条款 1 待复核): push2 解封补测四件——fs=m:116+t:3,t:4 港桶 total(~2927±) /
 * // fs=m:105,106,107 美桶 total / secid=116.HK00700 与 105.AAPL 行情 / 港美股板块 fs=b:BK****；
 * // 补测通过后在 QUOTE 降级链尾追加 EASTMONEY 港美股分支并回注方案 §10 R2 复核栏。
 * }</pre>
 */
@Component(TencentHkusQuoteSource.BEAN_NAME)
public class TencentHkusQuoteSource implements HkusQuoteSource {

    /** bean 名（快照服务 @Qualifier 注入主链位）。 */
    public static final String BEAN_NAME = "tencentHkusQuoteSource";

    private final TencentQuoteClient delegate;

    public TencentHkusQuoteSource(TencentQuoteClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String sourceCode() {
        return "tencent";
    }

    @Override
    public Map<String, Map<String, Object>> fetchBySubjectCodes(List<String> subjectCodes) {
        Map<String, String> codeToSymbol = new LinkedHashMap<>();
        for (String code : subjectCodes) {
            TencentQuoteClient.toTencentSymbol(SubjectCode.of(code))
                    .ifPresent(symbol -> codeToSymbol.put(code, symbol));
        }
        Map<String, Map<String, Object>> bySymbol =
                delegate.fetchQuotes(List.copyOf(codeToSymbol.values()));
        return restoreKeys(codeToSymbol, bySymbol);
    }

    /** 响应键（源符号）还原为标的码（行缺席标的无键——不视为错误）。 */
    static Map<String, Map<String, Object>> restoreKeys(
            Map<String, String> codeToSymbol, Map<String, Map<String, Object>> bySymbol) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        codeToSymbol.forEach(
                (code, symbol) -> {
                    Map<String, Object> fields = bySymbol.get(symbol);
                    if (fields != null) {
                        byCode.put(code, fields);
                    }
                });
        return byCode;
    }
}
