package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.HkusQuoteSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 港美股双源适配单测（M29 T252，ADR-0064 裁决 1）：委托客户端 Mock，验证端口适配三件事——① 标的码 → 源符号换算透传 （腾讯 US 大写 / 新浪 rt_hk
 * 实时前缀与 gb_ 小写）；② 响应键还原为标的码（行缺席标的无键）；③ sourceCode 落行留痕值。
 */
class HkusQuoteSourceAdaptersTest {

    @Test
    void tencentSource_convertsSymbolsAndRestoresSubjectCodeKeys() {
        TencentQuoteClient delegate = mock(TencentQuoteClient.class);
        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        Map<String, Object> aapl =
                Map.of(
                        "f43", new BigDecimal("338.40"),
                        "market_cap", new BigDecimal("49356.00844"),
                        "currency", "USD");
        bySymbol.put("usAAPL", aapl); // 腾讯控股行缺席（无效代码防御）
        ArgumentCaptor<List<String>> symbolsCaptor = ArgumentCaptor.forClass(List.class);
        when(delegate.fetchQuotes(symbolsCaptor.capture())).thenReturn(bySymbol);
        HkusQuoteSource source = new TencentHkusQuoteSource(delegate);

        Map<String, Map<String, Object>> rows =
                source.fetchBySubjectCodes(List.of("USAAPL", "HK00700"));

        assertThat(source.sourceCode()).isEqualTo("tencent");
        assertThat(symbolsCaptor.getValue()).containsExactly("usAAPL", "hk00700"); // US 大写 / HK 小写
        assertThat(rows).containsOnlyKeys("USAAPL"); // 行缺席标的无键
        assertThat(rows.get("USAAPL")).isEqualTo(aapl); // f 键中间结构原样透传
    }

    @Test
    void sinaSource_convertsRealtimeHkAndLowercaseUsSymbols() {
        SinaQuoteClient delegate = mock(SinaQuoteClient.class);
        Map<String, Map<String, Object>> bySymbol = new LinkedHashMap<>();
        bySymbol.put("rt_hk00700", Map.of("f43", new BigDecimal("432.000")));
        bySymbol.put("gb_aapl", Map.of("f43", new BigDecimal("338.4000")));
        ArgumentCaptor<List<String>> symbolsCaptor = ArgumentCaptor.forClass(List.class);
        when(delegate.fetchQuotes(symbolsCaptor.capture())).thenReturn(bySymbol);
        HkusQuoteSource source = new SinaHkusQuoteSource(delegate);

        Map<String, Map<String, Object>> rows =
                source.fetchBySubjectCodes(List.of("HK00700", "USAAPL"));

        assertThat(source.sourceCode()).isEqualTo("sina");
        assertThat(symbolsCaptor.getValue()).containsExactly("rt_hk00700", "gb_aapl");
        assertThat(rows).containsOnlyKeys("HK00700", "USAAPL");
    }

    @Test
    void sources_unsupportedPrefixSymbols_skippedWithoutKey() {
        TencentQuoteClient tencent = mock(TencentQuoteClient.class);
        when(tencent.fetchQuotes(anyList())).thenReturn(Map.of());
        SinaQuoteClient sina = mock(SinaQuoteClient.class);
        when(sina.fetchQuotes(anyList())).thenReturn(Map.of());

        // 板块/指数码不可换算港美股符号——空集透传（不视为错误）
        assertThat(new TencentHkusQuoteSource(tencent).fetchBySubjectCodes(List.of("BK0475")))
                .isEmpty();
        assertThat(new SinaHkusQuoteSource(sina).fetchBySubjectCodes(List.of("SH600519")))
                .isEmpty();
    }
}
