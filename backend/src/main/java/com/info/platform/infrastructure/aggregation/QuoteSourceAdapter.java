package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.FallbackChains;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
import com.info.platform.domain.aggregation.SourceProviders;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import com.info.platform.infrastructure.common.SourceCache;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 东方财富行情源真实 adapter（T03；ADR-0031 增腾讯备选降级，ADR-0033 升级降级链模型）。
 *
 * <p>真实接入东财 push2 {@code stock/get}：按 {@code subject.external_codes} 的 {@code eastmoney} secid
 * 取实时行情 （开高低收量额振幅），经 {@link FieldMapper} JSON 映射落 {@code SourceResult.data}。 与 {@link
 * MockQuoteSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36：{@code datasource.QUOTE.mode}
 * 分发，页面可热切换）。
 *
 * <h2>ADR-0033 · 降级链模型（承接 ADR-0031/0032 的 backupSource 开关）</h2>
 *
 * <p>取数按 {@code datasource.QUOTE.fallbackChain}（有序 provider 数组，首元素 = 主源，缺省 {@code
 * ["eastmoney","tencent"]}，代码内置注册表兜底）经 {@link FallbackChainRunner} 依次尝试：主源失败（HTTP
 * 错误/异常）<b>或空响应</b>（200 + data:null——封禁实测签名，与「盘外无数据」不可区分，降级腾讯两可：腾讯有数据即恢复、 也无数据则仍 MISSING）→ 记 WARN（带
 * provider 名与链位）→ 下一备选；全链失败走既有弹性降级（末级异常上抛，前级挂 {@code suppressed} 留诊断链），全链空响应 → MISSING。
 *
 * <ul>
 *   <li><b>DB 链优先</b>：{@code fallbackChain} 非空按序使用（可换主源/清备选，页面编辑）；空链 = 仅主源； 缺省按旧 {@code
 *       params.backupSource} 折算（auto→注册表全链、单值→单元素链，读取兼容逐步淘汰）
 *   <li><b>热生效</b>：每次取数读快照（ADR-0032 口径不变），页面保存下一次取数即新链，无需重启
 *   <li><b>来源标注</b>：{@code RawFetch.source} 标注实际命中 provider——主源「东方财富行情」、备选轮
 *       「东方财富行情→腾讯备选」、腾讯主源「腾讯行情」（健康徽章与连通性测试可区分兜底轮）
 * </ul>
 *
 * <p>腾讯路径产出<b>东财 f 键中间结构</b>（{@link TencentQuoteClient}，字段核对表见其 Javadoc），本类字段映射配置零改动；
 * OK/MISSING/FAILED 三态契约不变。
 *
 * <p>弹性（既有语义不变）：超时/重试/熔断/缓存由 {@link AbstractSourceAdapter} 模板统一编排——整条降级链 合用同一 ResilienceSpec
 * 预算（东财封禁失败为快速失败非挂起，预算内可完成两次调用；主源若挂起至超时，整次取数按既有超时降级）。 降级默认 MISSING——行情源挂不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 调研报告 §2.1/§4.1（🟢 实跑确认）。 f171 在报告 §4.1 落为振幅，但 §3.2 另注"含换手"，存在歧义；此处按 §4.1 映射矩阵落为
 * amplitude。东财 f 字段返回为字符串还是数值（fltt=2 下）亦待真实环境验证， {@link FieldMapper#toDecimal}/{@code toLong}
 * 对两者均兼容。
 */
public class QuoteSourceAdapter extends AbstractSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(QuoteSourceAdapter.class);

    /** subject.external_codes 中东财 secid 的键名。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** 强制 tencent 单源（链 ["tencent"] 主源位）的来源标注。 */
    static final String TENCENT_ONLY_LABEL = "腾讯行情";

    /** 旧备选源开关的运行时键（缺省回落值构造期解析用；ADR-0033 起读取兼容口径）。 */
    static final String FALLBACK_CONFIG_KEY = "datasource.QUOTE.params.backupSource";

    /** 降级链的运行时键（ADR-0033 写路径统一；DB 链优先，缺省按旧键折算）。 */
    static final String CHAIN_CONFIG_KEY = "datasource.QUOTE.fallbackChain";

    /** 该源可用 provider 注册表（代码事实，首元素 = 默认主源）。 */
    private static final List<SourceProvider> PROVIDERS =
            SourceProviders.providers(SourceCode.QUOTE);

    /** 业务名（来源标注与日志的链位名词：「东方财富行情」）。 */
    private static final String SOURCE_NOUN = "行情";

    private final EastMoneyClient client;
    private final TencentQuoteClient tencentClient;

    /** 备选源开关回落值（纯构造单测/配置中心缺失时；生产装配取 {@code DataSourceDefaults} 缺省 auto）。 */
    private final BackupSourceMode fallbackMode;

    private final List<FieldMapping> mapping;

    public QuoteSourceAdapter(
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
        return FallbackChainRunner.fetch(
                SOURCE_NOUN,
                currentChain(),
                (provider, label) -> fetchByProvider(subject, provider, label));
    }

    /** 按 provider 取数（链已按注册表校验/兜底，枚举穷举无未知分支）。 */
    private Optional<RawFetch> fetchByProvider(
            Subject subject, SourceProvider provider, String label) throws Exception {
        return switch (provider) {
            case EASTMONEY -> fetchFromEastMoney(subject, label);
            case TENCENT -> fetchFromTencent(subject, label);
            default -> throw new IllegalArgumentException("行情源未接入 provider: " + provider);
        };
    }

    /**
     * 当前降级链（ADR-0033 热读）：每次取数读 {@code datasource.QUOTE} 快照现算（页面保存即热生效）—— DB {@code fallbackChain}
     * 优先，缺省按旧 {@code params.backupSource} 折算，再缺回落注册表全链兜底。 配置中心缺失（纯构造单测）回落构造期开关；DB
     * 手改坏值（链成员越界/旧开关非法）不阻断取数——WARN 后回落全链（写路径有校验，读取侧兜底）。
     */
    List<SourceProvider> currentChain() {
        if (configCenter == null) {
            return switch (fallbackMode) {
                case EASTMONEY -> List.of(SourceProvider.EASTMONEY);
                case TENCENT -> List.of(SourceProvider.TENCENT);
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

    /** 东财路径（既有逻辑，label 按链位标注）：secid 缺失/盘外空 data → empty（→ 尝试下一级/MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject, String label) throws Exception {
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
        return data.map(node -> new RawFetch(node, label, Instant.now()));
    }

    /** 腾讯路径：按内部标的码换算符号拉取（不依赖 eastmoney secid），产出东财 f 键中间结构。 */
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
