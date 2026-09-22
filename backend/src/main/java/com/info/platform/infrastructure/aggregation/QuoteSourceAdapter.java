package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 东方财富行情源真实 adapter（T03）。
 *
 * <p>真实接入东财 push2 {@code stock/get}：按 {@code subject.external_codes} 的 {@code eastmoney} secid
 * 取实时行情 （开高低收量额振幅），经 {@link FieldMapper} JSON 映射落 {@code SourceResult.data}。 与 {@link
 * MockQuoteSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36：{@code datasource.QUOTE.mode}
 * 分发，页面可热切换）。
 *
 * <p>弹性：超时 1.5s、重试 0（实时行情重试无意义、热路径，对齐技术方案 §4.3 流程 1 行情）。降级默认 MISSING——行情源挂不阻断 详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 调研报告 §2.1/§4.1（🟢 实跑确认）。 f171 在报告 §4.1 落为振幅，但 §3.2 另注"含换手"，存在歧义；此处按 §4.1 映射矩阵落为
 * amplitude，<b>语义待真实环境验证</b>。东财 f 字段返回为字符串还是数值（fltt=2 下）亦待真实环境验证， {@link
 * FieldMapper#toDecimal}/{@code toLong} 对两者均兼容。
 */
public class QuoteSourceAdapter extends AbstractSourceAdapter {

    /** subject.external_codes 中东财 secid 的键名。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    private final EastMoneyClient client;
    private final List<FieldMapping> mapping;

    public QuoteSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.mapping = fieldMapper.loadMapping("field-mapping/eastmoney-quote.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.QUOTE;
    }

    @Override
    protected String sourceLabel() {
        return "东方财富行情";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return mapping;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        Map<String, String> externalCodes = subject.getExternalCodes();
        if (externalCodes == null) {
            return Optional.empty();
        }
        String secid = externalCodes.get(EASTMONEY_SECID_KEY);
        if (secid == null || secid.isBlank()) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> data = client.fetchQuote(secid);
        // data 为空（盘外/停牌当日无数据）→ MISSING（成功调用，非异常）
        return data.map(node -> new RawFetch(node, sourceLabel(), Instant.now()));
    }
}
