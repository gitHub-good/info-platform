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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 东方财富估值源真实 adapter（T04；ADR-0031 增腾讯备选降级）。
 *
 * <p>真实接入东财 push2 {@code stock/get}（与行情同端点，Spike-1 §3.1「端点收敛」）：按 {@code subject.external_codes} 的
 * {@code eastmoney} secid + 估值 f 字段（f162/f167）取估值原始数据，经 {@link FieldMapper} JSON 映射落 {@code
 * SourceResult.data}。复用 {@link EastMoneyClient#fetchValuation(String)}（行情/估值同端点不同 fields，省一个客户端类）。
 * 与 {@link MockValuationSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 *
 * <h2>ADR-0031 · 腾讯备选源与自动降级 / ADR-0032 · 开关热化</h2>
 *
 * <p>与 {@link QuoteSourceAdapter} 同模式（运行时参数 {@code datasource.VALUATION.params.backupSource: auto |
 * eastmoney | tencent}，缺省 auto，每次取数用时读快照——原 yml {@code adapter.valuation-source} 启动期绑定， ADR-0032
 * 热化）： 东财失败或空响应 → WARN → 腾讯按内部标的码重拉，PE/PB 从腾讯字段映射为东财 f 键 （A 股 PE@52/PB@46、港股 PE@39/PB@58，核对表见
 * {@link TencentQuoteClient} Javadoc）——本类字段映射配置零改动。 东财港股 f162 实测返回 '-'（2026-09-24 curl
 * 116.00700）——恰好落在可降级路径，腾讯港股位可补齐 PE。
 *
 * <p>弹性（既有语义不变）：超时/重试运行时读 {@code datasource.VALUATION}（种子默认超时 2s 重试 0）。降级默认 MISSING——估值源挂不阻断其他分区。
 *
 * <p>字段语义：f162→peTtm、f167→pb（to_decimal）。 <b>语义为推测、待真实环境多股实测确认</b>：2026-09-21 curl 茅台 1.600519 得
 * f162=17.57（茅台 PE~17 合理，推测 PE-TTM）、f167=6.23（茅台 PB~6 合理，推测 PB）。 2026-09-24 交叉核对补充：腾讯 52 位（PE-动口径）
 * 与 f162 茅台 17.37 / 平安 4.27 <b>数值完全一致</b>，46 位与 f167 两样本一致——备选源按数值一致位映射（ADR-0031）。
 * f163=19.01、f173=16.75 精确语义（PE 静态/动态变体？）暂未确认，<b>未纳入映射</b>，待 F10 文档或更多样本对照后补入；若实测 f162/f167 与 PE/PB
 * 不符，按 Spike-1 §7 拍板点 3 切 tushare {@code daily_basic}。东财 f 字段在 fltt=2 下返回数值（已 curl 确认），{@link
 * FieldMapper#toDecimal} 对数值与字符串均兼容。
 */
public class ValuationSourceAdapter extends AbstractSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ValuationSourceAdapter.class);

    /** subject.external_codes 中东财 secid 的键名（与 QuoteSourceAdapter 一致）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** auto 降级轮来源标注（健康徽章可区分「东财失败但腾讯兜底成功」）。 */
    static final String TENCENT_FALLBACK_SUFFIX = "→腾讯备选";

    /** 强制 tencent 单源的来源标注。 */
    static final String TENCENT_ONLY_LABEL = "腾讯估值";

    /** 备选源开关的运行时键（缺省回落值构造期解析用；热读键名见 {@link #currentMode()}）。 */
    static final String FALLBACK_CONFIG_KEY = "datasource.VALUATION.params.backupSource";

    private final EastMoneyClient client;
    private final TencentQuoteClient tencentClient;

    /** 备选源开关回落值（纯构造单测/配置中心缺失时；生产装配取 {@code DataSourceDefaults} 缺省 auto）。 */
    private final BackupSourceMode fallbackMode;

    private final List<FieldMapping> mapping;

    public ValuationSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            TencentQuoteClient tencentClient,
            String mode) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.tencentClient = tencentClient;
        this.fallbackMode = BackupSourceMode.parse(mode, FALLBACK_CONFIG_KEY);
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
    protected Optional<RawFetch> doFetch(Subject subject) throws Exception {
        return switch (currentMode()) {
            case EASTMONEY -> fetchFromEastMoney(subject);
            case TENCENT -> fetchFromTencent(subject, TENCENT_ONLY_LABEL);
            case AUTO -> fetchAuto(subject);
        };
    }

    /**
     * 当前备选源开关（ADR-0032 热读）：每次取数读 {@code datasource.VALUATION.params.backupSource} 快照。
     * 配置中心缺失（纯构造单测）回落构造期缺省；DB 手改坏值 WARN 后回落 auto（写路径有 oneOf 校验，此处兜底）。
     */
    BackupSourceMode currentMode() {
        if (configCenter == null) {
            return fallbackMode;
        }
        String raw = configCenter.dataSource(sourceCode()).paramString("backupSource", null);
        if (raw == null || raw.isBlank()) {
            return fallbackMode;
        }
        try {
            return BackupSourceMode.parse(raw, FALLBACK_CONFIG_KEY);
        } catch (IllegalArgumentException e) {
            log.warn("备选源开关取值非法，回落 auto key={} raw='{}'", FALLBACK_CONFIG_KEY, raw);
            return BackupSourceMode.AUTO;
        }
    }

    /** auto：东财成功直用；东财失败/空响应 → WARN → 腾讯兜底；腾讯也失败按既有语义降级（东财原因挂 suppressed）。 */
    private Optional<RawFetch> fetchAuto(Subject subject) throws Exception {
        Optional<RawFetch> fromEast = Optional.empty();
        RuntimeException eastFailure = null;
        try {
            fromEast = fetchFromEastMoney(subject);
        } catch (RuntimeException e) {
            eastFailure = e;
        }
        if (fromEast.isPresent()) {
            return fromEast;
        }
        log.warn(
                "东财估值取数失败或空响应，auto 自动降级腾讯备选源重拉 subjectCode={} eastFailure={}",
                subject.getSubjectCode(),
                eastFailure == null ? "空响应" : eastFailure.toString());
        try {
            return fetchFromTencent(subject, sourceLabel() + TENCENT_FALLBACK_SUFFIX);
        } catch (RuntimeException tencentFailure) {
            if (eastFailure != null) {
                tencentFailure.addSuppressed(eastFailure);
            }
            throw tencentFailure;
        }
    }

    /** 东财路径（既有逻辑原样抽方法）：secid 缺失/data 空 → empty（→ MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject) throws Exception {
        Map<String, String> externalCodes = subject.getExternalCodes();
        if (externalCodes == null) {
            return Optional.empty();
        }
        String secid = externalCodes.get(EASTMONEY_SECID_KEY);
        if (secid == null || secid.isBlank()) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> data = client.fetchValuation(secid);
        // data 为空（盘外/停牌当日无估值列）→ MISSING（成功调用，非异常）
        return data.map(node -> new RawFetch(node, sourceLabel(), Instant.now()));
    }

    /** 腾讯路径：按内部标的码换算符号拉取（与行情同端点同响应，仅估值 f 键被字段映射白名单消费）。 */
    private Optional<RawFetch> fetchFromTencent(Subject subject, String label) {
        Optional<String> symbol = TencentQuoteClient.toTencentSymbol(subject.getSubjectCode());
        if (symbol.isEmpty()) {
            log.warn("标的码无法换算腾讯符号，跳过备选源 subjectCode={}", subject.getSubjectCode());
            return Optional.empty();
        }
        return tencentClient
                .fetchQuote(symbol.get())
                .map(data -> new RawFetch(data, label, Instant.now()));
    }
}
