package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 东方财富财务源真实 adapter（T04）。
 *
 * <p>真实接入东财 datacenter {@code RPT_F10_FINANCE_MAINFINADATA}：按 {@code subject.external_codes} 派生的 6
 * 位证券代码取最新报告期一条主财务指标（营收/归母净利/净利率/毛利率/ROE/报告期），经 {@link FieldMapper} JSON 映射落 {@code
 * SourceResult.data}。 与 {@link MockFinanceSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36
 * 热切换）。
 *
 * <p>代码派生：datacenter filter 用 6 位代码（如 {@code 600519}），非 secid（如 {@code 1.600519}）。优先取 {@code
 * eastmoney_code} 键； 缺省则从 {@code eastmoney} secid 按 {@code .} 切分派生（Spike-1 §5：secid 与纯代码可互相派生），兼容当前
 * V2 种子（仅存 secid）。 两者皆缺 → MISSING（不发请求）。
 *
 * <p>弹性：超时 2s、重试 0（季频只读、重试无收益，对齐技术方案 §4.3 流程 1 财务源）。降级默认 MISSING——财务源挂不阻断其他分区。
 *
 * <p>字段语义依据 Spike-1 §2.2/§4.2（🟢 实跑确认），2026-09-21 curl 茅台 600519 二次确认：
 * TOTALOPERATEREVE/PARENTNETPROFIT/XSJLL/XSMLL/ROEJQ 返回数值、 REPORT_DATE 为 {@code yyyy-MM-dd
 * HH:mm:ss} 字符串。响应结构为 {@code result.data[0]}（实测；Spike-1 §6.2 载为 {@code data.list[0]} 与实跑不符，本
 * adapter 按实测实现）。
 */
public class FinanceSourceAdapter extends AbstractSourceAdapter {

    /** subject.external_codes 中东财 6 位代码的键名（优先取，Spike-1 §5 建议两者都存）。 */
    private static final String EASTMONEY_CODE_KEY = "eastmoney_code";

    /** subject.external_codes 中东财 secid 的键名（派生 6 位代码的回退来源）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    private final EastMoneyFinanceClient client;
    private final List<FieldMapping> mapping;

    public FinanceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyFinanceClient client) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.mapping = fieldMapper.loadMapping("field-mapping/eastmoney-finance.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.FINANCE;
    }

    /** T31：F10 主财务指标为上市公司专属，本源仅支持股票。 */
    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        return EnumSet.of(SubjectType.STOCK);
    }

    @Override
    protected String sourceLabel() {
        return "东方财富财务";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return mapping;
    }

    @Override
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        String securityCode = resolveSecurityCode(subject);
        if (securityCode == null) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> data = client.fetchFinance(securityCode);
        // 无最新报告期 / result.data 空 → MISSING（成功调用，非异常）
        return data.map(node -> new RawFetch(node, sourceLabel(), Instant.now()));
    }

    /**
     * 取东财 6 位证券代码：优先 {@code eastmoney_code} 键；缺省从 {@code eastmoney} secid（如 {@code 1.600519}）按首个
     * {@code .} 切分派生为 {@code 600519}。 两者皆缺/空 → null（→ MISSING，不发 HTTP）。
     */
    private String resolveSecurityCode(Subject subject) {
        Map<String, String> externalCodes = subject.getExternalCodes();
        if (externalCodes == null) {
            return null;
        }
        String code = externalCodes.get(EASTMONEY_CODE_KEY);
        if (code != null && !code.isBlank()) {
            return code;
        }
        String secid = externalCodes.get(EASTMONEY_SECID_KEY);
        if (secid == null || secid.isBlank()) {
            return null;
        }
        int dot = secid.indexOf('.');
        return dot >= 0 ? secid.substring(dot + 1) : secid;
    }
}
