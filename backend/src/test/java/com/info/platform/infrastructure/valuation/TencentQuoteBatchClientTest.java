package com.info.platform.infrastructure.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.infrastructure.aggregation.TencentQuoteClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TencentQuoteBatchClient 委托适配单测（T170）：fetchQuotes/toTencentSymbol 纯透传既有客户端（端口零语义增删）。 */
class TencentQuoteBatchClientTest {

    private TencentQuoteClient delegate;
    private TencentQuoteBatchClient adapter;

    @BeforeEach
    void setUp() {
        delegate = mock(TencentQuoteClient.class);
        adapter = new TencentQuoteBatchClient(delegate);
    }

    @Test
    void fetchQuotes_delegatesToClient() {
        Map<String, Map<String, Object>> rows = Map.of("sh600519", Map.of("f43", "x"));
        when(delegate.fetchQuotes(List.of("sh600519"))).thenReturn(rows);

        assertThat(adapter.fetchQuotes(List.of("sh600519"))).isSameAs(rows);
    }

    @Test
    void toTencentSymbol_delegatesWithSubjectCode() {
        // 内部码 → 腾讯符号静态转换经端口可达（unsupported 前缀 empty 同透传）
        assertThat(adapter.toTencentSymbol("SH600519"))
                .isEqualTo(TencentQuoteClient.toTencentSymbol(SubjectCode.of("SH600519")));
        assertThat(adapter.toTencentSymbol("SECTOR01")).isEqualTo(Optional.empty());
    }
}
