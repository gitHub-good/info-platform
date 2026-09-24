package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.FallbackChains;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProviders;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.RuntimeDataSource;
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
 * 东方财富估值源真实 adapter（T04；ADR-0031 增腾讯备选降级，ADR-0033 升级降级链模型）。
 *
 * <p>真实接入东财 push2 {@code stock/get}（与行情同端点，Spike-1 §3.1「端点收敛」）：按 {@code subject.external_codes} 的
 * {@code eastmoney} secid + 估值 f 字段（f162/f167）取估值原始数据，经 {@link FieldMapper} JSON 映射落 {@code
 * SourceResult.data}。复用 {@link EastMoneyClient#fetchValuation(String)}（行情/估值同端点不同 fields，省一个客户端类）。
 * 与 {@link MockValuationSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36 热切换）。
 *
 * <h2>ADR-0033 · 降级链模型（承接 ADR-0031/0032 的 backupSource 开关）</h2>
 *
 * <p>与 {@link QuoteSourceAdapter} 同模式：取数按 {@code datasource.VALUATION.fallbackChain}（缺省 {@code
 * ["eastmoney","tencent"]}）经 {@link FallbackChainRunner} 依次尝试，每级 WARN 留痕带 provider 名， {@code
 * RawFetch.source} 标注实际命中 provider；每次取数读快照热生效，DB 链优先、旧 {@code params.backupSource} 折算兼容、注册表全链兜底。
 * 东财失败或空响应 → 腾讯按内部标的码重拉，PE/PB 从腾讯字段映射为东财 f 键 （A 股 PE@52/PB@46、港股 PE@39/PB@58，核对表见 {@link
 * TencentQuoteClient} Javadoc）——本类字段映射配置零改动。 东财港股 f162 实测返回 '-'（2026-09-24 curl
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

    /** 强制 tencent 单源（链 ["tencent"] 主源位）的来源标注。 */
    static final String TENCENT_ONLY_LABEL = "腾讯估值";

    /** 旧备选源开关的运行时键（缺省回落值构造期解析用；ADR-0033 起读取兼容口径）。 */
    static final String FALLBACK_CONFIG_KEY = "datasource.VALUATION.params.backupSource";

    /** 降级链的运行时键（ADR-0033 写路径统一；DB 链优先，缺省按旧键折算）。 */
    static final String CHAIN_CONFIG_KEY = "datasource.VALUATION.fallbackChain";

    /** 该源可用 provider 注册表（代码事实，首元素 = 默认主源）。 */
    private static final List<String> PROVIDERS = SourceProviders.providers(SourceCode.VALUATION);

    /** 业务名（来源标注与日志的链位名词：「东方财富估值」）。 */
    private static final String SOURCE_NOUN = "估值";

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
        return FallbackChainRunner.fetch(
                SOURCE_NOUN,
                currentChain(),
                (provider, label) -> fetchByProvider(subject, provider, label));
    }

    /** 按 provider 取数：注册表外 provider 不可达（链已按注册表校验/兜底，防御性快速失败）。 */
    private Optional<RawFetch> fetchByProvider(Subject subject, String provider, String label)
            throws Exception {
        return switch (provider) {
            case "eastmoney" -> fetchFromEastMoney(subject, label);
            case "tencent" -> fetchFromTencent(subject, label);
            default -> throw new IllegalArgumentException("估值源未知 provider: " + provider);
        };
    }

    /**
     * 当前降级链（ADR-0033 热读）：每次取数读 {@code datasource.VALUATION} 快照现算——DB {@code fallbackChain} 优先，缺省按旧
     * {@code params.backupSource} 折算，再缺回落注册表全链兜底。 配置中心缺失（纯构造单测）回落构造期开关；DB 手改坏值 WARN 后回落全链（读取侧兜底）。
     */
    List<String> currentChain() {
        if (configCenter == null) {
            return switch (fallbackMode) {
                case EASTMONEY -> List.of("eastmoney");
                case TENCENT -> List.of("tencent");
                case AUTO -> PROVIDERS;
            };
        }
        RuntimeDataSource config = configCenter.dataSource(sourceCode());
        List<String> rawChain = config.fallbackChain();
        if (rawChain != null
                && !rawChain.isEmpty()
                && !FallbackChains.isValidChain(rawChain, PROVIDERS)) {
            log.warn("降级链配置非法，回落注册表全链 key={} raw={}", CHAIN_CONFIG_KEY, rawChain);
            rawChain = null;
        }
        String legacy = config.paramString("backupSource", null);
        if (!FallbackChains.isValidLegacy(legacy, PROVIDERS)) {
            log.warn("备选源开关取值非法，回落全链 key={} raw='{}'", FALLBACK_CONFIG_KEY, legacy);
            legacy = null;
        }
        return FallbackChains.resolve(rawChain, legacy, PROVIDERS);
    }

    /** 东财路径（既有逻辑，label 按链位标注）：secid 缺失/data 空 → empty（→ 尝试下一级/MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject, String label) throws Exception {
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
        return data.map(node -> new RawFetch(node, label, Instant.now()));
    }

    /** 腾讯路径：按内部标的码换算符号拉取（与行情同端点同响应，仅估值 f 键被字段映射白名单消费）。 */
    private Optional<RawFetch> fetchFromTencent(Subject subject, String label) {
        Optional<String> symbol = TencentQuoteClient.toTencentSymbol(subject.getSubjectCode());
        if (symbol.isEmpty()) {
            log.warn("标的码无法换算腾讯符号，跳过该链位 subjectCode={}", subject.getSubjectCode());
            return Optional.empty();
        }
        return tencentClient
                .fetchQuote(symbol.get())
                .map(data -> new RawFetch(data, label, Instant.now()));
    }
}
