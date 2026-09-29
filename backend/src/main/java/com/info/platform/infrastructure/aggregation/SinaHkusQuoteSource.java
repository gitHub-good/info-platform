package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.HkusQuoteSource;
import com.info.platform.domain.aggregation.SubjectCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link HkusQuoteSource} 端口的新浪备链实现（M29 T252，ADR-0064 裁决 1）：纯委托 {@link SinaQuoteClient} （{@code
 * rt_hk_} 实时前缀 / {@code gb_} 小写 ticker 符号换算 + f 键中间结构对齐）——仅腾讯<b>整轮失败</b>时由快照轮启用 （轮级降级非逐标的），{@code
 * sourceCode="sina"} 落 {@code market_daily_snapshot.source} 留痕。
 */
@Component(SinaHkusQuoteSource.BEAN_NAME)
public class SinaHkusQuoteSource implements HkusQuoteSource {

    /** bean 名（快照服务 @Qualifier 注入备链位）。 */
    public static final String BEAN_NAME = "sinaHkusQuoteSource";

    private final SinaQuoteClient delegate;

    public SinaHkusQuoteSource(SinaQuoteClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String sourceCode() {
        return "sina";
    }

    @Override
    public Map<String, Map<String, Object>> fetchBySubjectCodes(List<String> subjectCodes) {
        Map<String, String> codeToSymbol = new LinkedHashMap<>();
        for (String code : subjectCodes) {
            SinaQuoteClient.toSinaSymbol(SubjectCode.of(code))
                    .ifPresent(symbol -> codeToSymbol.put(code, symbol));
        }
        Map<String, Map<String, Object>> bySymbol =
                delegate.fetchQuotes(List.copyOf(codeToSymbol.values()));
        return TencentHkusQuoteSource.restoreKeys(codeToSymbol, bySymbol);
    }
}
