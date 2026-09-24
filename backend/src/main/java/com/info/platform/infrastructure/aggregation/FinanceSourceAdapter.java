package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.FallbackChains;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceProvider;
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
 * 东方财富财务源真实 adapter（T04；ADR-0034 增新浪备选降级——第四个降级链消费点）。
 *
 * <p>真实接入东财 datacenter {@code RPT_F10_FINANCE_MAINFINADATA}：按 {@code subject.external_codes} 派生的 6
 * 位证券代码取最新报告期一条主财务指标（营收/归母净利/净利率/毛利率/ROE/报告期），经 {@link FieldMapper} JSON 映射落 {@code
 * SourceResult.data}。 与 {@link MockFinanceSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36
 * 热切换）。
 *
 * <h2>ADR-0034 · 降级链模型（复制 ADR-0031/0033 行情/估值先例）</h2>
 *
 * <p>取数按 {@code datasource.FINANCE.fallbackChain}（缺省 {@code ["eastmoney","sina"]}，注册表全链兜底）经 {@link
 * FallbackChainRunner} 依次尝试：东财失败（HTTP 错误/异常）<b>或空响应</b>（9501 事故签名：200 + {@code result:null}
 * 错误体——f3cdcc1 修复对象，空响应与「无数据」不可区分，降级新浪两可）→ 记 WARN（带 provider 名与链位）→ 新浪按 6 位代码重拉两页（vFD
 * 利润表+财务指标，~0.4s）；全链失败走既有弹性降级（末级异常上抛，前级挂 {@code suppressed} 留诊断链），全链空响应 → MISSING。
 *
 * <ul>
 *   <li><b>DB 链优先</b>：{@code fallbackChain} 非空按序使用（可换主源/清备选，页面编辑）；空链 = 仅主源；缺省回落注册表全链。 本源<b>无旧
 *       {@code params.backupSource} 单值键历史</b>（ADR-0033 前财务源无备选开关），legacy 恒 null
 *   <li><b>热生效</b>：每次取数读快照现算（页面保存下一次取数即新链，无需重启）
 *   <li><b>来源标注</b>：{@code RawFetch.source} 标注实际命中 provider——链位 0「东方财富财务」/ 链位 1 「东方财富财务→新浪备选」/强制
 *       ["sina"] 单源时「新浪财务」（健康徽章与连通性测试可区分兜底轮）
 * </ul>
 *
 * <p>新浪路径产出<b>东财 F10 键中间结构</b>（{@link SinaFinanceClient}，行名变体/万元转元/报告期对位核对表见其
 * Javadoc），本类字段映射配置（{@code eastmoney-finance.json}）零改动；OK/MISSING/FAILED 三态契约不变。
 *
 * <p>代码派生：datacenter filter 用 6 位代码（如 {@code 600519}），非 secid（如 {@code 1.600519}）。优先取 {@code
 * eastmoney_code} 键； 缺省则从 {@code eastmoney} secid 按 {@code .} 切分派生（Spike-1 §5：secid 与纯代码可互相派生）。
 * 两者皆缺 → MISSING（不发请求）；新浪路径复用同一 6 位代码（vFD 按 6 位代码查询）。
 *
 * <p>弹性（既有语义不变，ADR-0034 §3 预算两轮合用）：超时/重试/熔断/缓存由 {@link AbstractSourceAdapter} 模板统一编排——整条降级链合用同一
 * ResilienceSpec 预算（noRetry 2s：东财快速失败形态 ~0.1s + 新浪两页 ~0.4s 预算内； 东财挂起至超时则兜底不保证，沿 ADR-0031 已知限制）。降级默认
 * MISSING——财务源挂不阻断其他分区。
 */
public class FinanceSourceAdapter extends AbstractSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(FinanceSourceAdapter.class);

    /** subject.external_codes 中东财 6 位代码的键名（优先取，Spike-1 §5 建议两者都存）。 */
    private static final String EASTMONEY_CODE_KEY = "eastmoney_code";

    /** subject.external_codes 中东财 secid 的键名（派生 6 位代码的回退来源）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** 降级链的运行时键（ADR-0033 写路径统一；DB 链优先，缺省回落注册表全链）。 */
    static final String CHAIN_CONFIG_KEY = "datasource.FINANCE.fallbackChain";

    /** 该源可用 provider 注册表（代码事实，首元素 = 默认主源）。 */
    private static final List<SourceProvider> PROVIDERS =
            SourceProviders.providers(SourceCode.FINANCE);

    /** 业务名（来源标注与日志的链位名词：「东方财富财务」）。 */
    private static final String SOURCE_NOUN = "财务";

    private final EastMoneyFinanceClient client;
    private final SinaFinanceClient sinaClient;
    private final List<FieldMapping> mapping;

    public FinanceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyFinanceClient client,
            SinaFinanceClient sinaClient) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.sinaClient = sinaClient;
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
        return FallbackChainRunner.fetch(
                SOURCE_NOUN,
                currentChain(),
                (provider, label) -> fetchByProvider(subject, provider, label));
    }

    /** 按 provider 取数（链已按注册表校验/兜底，注册表外 provider 防御性快速失败）。 */
    private Optional<RawFetch> fetchByProvider(
            Subject subject, SourceProvider provider, String label) throws Exception {
        return switch (provider) {
            case EASTMONEY -> fetchFromEastMoney(subject, label);
            case SINA -> fetchFromSina(subject, label);
            default -> throw new IllegalArgumentException("财务源未接入 provider: " + provider);
        };
    }

    /**
     * 当前降级链（ADR-0033 热读）：每次取数读 {@code datasource.FINANCE} 快照现算（页面保存即热生效）—— DB {@code fallbackChain}
     * 优先，缺省回落注册表全链兜底。 本源无旧 {@code params.backupSource} 单值键历史（ADR-0033 前财务源无备选开关），legacy 恒传 null；DB
     * 手改坏值（链成员越界）不阻断取数——WARN 后回落全链（读取侧兜底）。
     */
    List<SourceProvider> currentChain() {
        if (configCenter == null) {
            return PROVIDERS;
        }
        RuntimeDataSource config = configCenter.dataSource(sourceCode());
        List<String> rawChain = config.fallbackChain();
        if (rawChain != null
                && !rawChain.isEmpty()
                && !FallbackChains.isValidChain(rawChain, PROVIDERS)) {
            log.warn("降级链配置非法，回落注册表全链 key={} raw={}", CHAIN_CONFIG_KEY, rawChain);
            rawChain = null;
        }
        return FallbackChains.resolve(rawChain, null, PROVIDERS);
    }

    /** 东财路径（既有逻辑，label 按链位标注）：无最新报告期 / result.data 空 → empty（→ 尝试下一级/MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject, String label) throws Exception {
        String securityCode = resolveSecurityCode(subject);
        if (securityCode == null) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> data = client.fetchFinance(securityCode);
        return data.map(node -> new RawFetch(node, label, Instant.now()));
    }

    /** 新浪路径：复用同一 6 位证券代码拉两页，产出东财 F10 键中间结构（字段映射零改动）。 */
    private Optional<RawFetch> fetchFromSina(Subject subject, String label) {
        String securityCode = resolveSecurityCode(subject);
        if (securityCode == null) {
            return Optional.empty();
        }
        return sinaClient
                .fetchFinance(securityCode)
                .map(data -> new RawFetch(data, label, Instant.now()));
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
