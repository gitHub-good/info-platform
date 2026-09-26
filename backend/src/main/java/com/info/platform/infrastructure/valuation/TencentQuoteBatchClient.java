package com.info.platform.infrastructure.valuation;

import com.info.platform.application.valuation.QuoteBatchClient;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.infrastructure.aggregation.TencentQuoteClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link QuoteBatchClient} 端口的腾讯实现（M20 T170，ADR-0058 裁决 5.3）：纯委托既有 {@link TencentQuoteClient}（50
 * 只/块自动分块 + GBK 解码 + f 键映射零改动复用）——备选源对快照层完全透明。
 */
@Component
public class TencentQuoteBatchClient implements QuoteBatchClient {

    private final TencentQuoteClient delegate;

    public TencentQuoteBatchClient(TencentQuoteClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Map<String, Map<String, Object>> fetchQuotes(List<String> tencentSymbols) {
        return delegate.fetchQuotes(tencentSymbols);
    }

    @Override
    public Optional<String> toTencentSymbol(String subjectCode) {
        return TencentQuoteClient.toTencentSymbol(SubjectCode.of(subjectCode));
    }
}
