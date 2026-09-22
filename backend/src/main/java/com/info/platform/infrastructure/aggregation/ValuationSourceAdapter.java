package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.ResilienceSpec;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 东方财富估值源真实 adapter（T04）。
 *
 * <p>真实接入东财 push2 {@code stock/get}（与行情同端点，Spike-1 §3.1「端点收敛」）：按 {@code subject.external_codes} 的
 * {@code eastmoney} secid + 估值 f 字段（f162/f167）取估值原始数据，经 {@link FieldMapper} JSON 映射落 {@code
 * SourceResult.data}。复用 {@link EastMoneyClient#fetch(String, String)}（行情/估值同端点不同 fields，省一个客户端类）。 与
 * {@link MockValuationSourceAdapter} 互斥——{@code adapter.mock.enabled=false} 时装配本类。
 *
 * <p>弹性：超时 2s、重试 0（对齐技术方案 §4.3 流程 1 估值源）。降级默认 MISSING——估值源挂不阻断其他分区。
 *
 * <p>字段语义：f162→peTtm、f167→pb（to_decimal）。 <b>语义为推测、待真实环境多股实测确认</b>：2026-09-21 curl 茅台 1.600519 得
 * f162=17.57（茅台 PE~17 合理，推测 PE-TTM）、f167=6.23（茅台 PB~6 合理，推测 PB）。 f163=19.01、f173=16.75 精确语义（PE
 * 静态/动态变体？）暂未确认，<b>未纳入映射</b>，待 F10 文档或更多样本对照后补入；若实测 f162/f167 与 PE/PB 不符，按 Spike-1 §7 拍板点 3 切
 * tushare {@code daily_basic}。东财 f 字段在 fltt=2 下返回数值（已 curl 确认），{@link FieldMapper#toDecimal}
 * 对数值与字符串均兼容。
 */
@Component
@ConditionalOnProperty(name = "adapter.mock.enabled", havingValue = "false")
public class ValuationSourceAdapter extends AbstractSourceAdapter {

    /** subject.external_codes 中东财 secid 的键名（与 QuoteSourceAdapter 一致）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final EastMoneyClient client;
    private final String valuationFields;
    private final List<FieldMapping> mapping;

    public ValuationSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            @Value("${adapter.eastmoney.valuation-fields:f57,f162,f167}") String valuationFields) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.valuationFields = valuationFields;
        this.mapping = fieldMapper.loadMapping("field-mapping/eastmoney-valuation.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.VALUATION;
    }

    /** T31：PE-TTM/PB 为个股估值语义（f162/f167 按 stock/get 个股字段实测），本源仅支持股票。 */
    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        return EnumSet.of(SubjectType.STOCK);
    }

    @Override
    protected String sourceLabel() {
        return "东方财富估值";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return mapping;
    }

    @Override
    protected ResilienceSpec resilienceSpec() {
        return ResilienceSpec.noRetry(TIMEOUT);
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
        Optional<Map<String, Object>> data = client.fetch(secid, valuationFields);
        // data 为空（盘外/停牌当日无估值列）→ MISSING（成功调用，非异常）
        return data.map(node -> new RawFetch(node, sourceLabel(), Instant.now()));
    }
}
