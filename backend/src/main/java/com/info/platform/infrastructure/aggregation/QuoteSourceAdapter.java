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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 东方财富行情源真实 adapter（T03；ADR-0031 增腾讯备选降级）。
 *
 * <p>真实接入东财 push2 {@code stock/get}：按 {@code subject.external_codes} 的 {@code eastmoney} secid
 * 取实时行情 （开高低收量额振幅），经 {@link FieldMapper} JSON 映射落 {@code SourceResult.data}。 与 {@link
 * MockQuoteSourceAdapter} 经 {@link RoutingSourceAdapter} 运行时路由共存（T36：{@code datasource.QUOTE.mode}
 * 分发，页面可热切换）。
 *
 * <h2>ADR-0031 · 腾讯备选源与自动降级</h2>
 *
 * <p>push2 IP 封禁期间行情全站瘫痪（详情页/自选页/异动检测依赖本源）。本 adapter 组合东财 + 腾讯两个 client （{@code
 * BackupSourceMode}，配置 {@code adapter.quote-source: auto | eastmoney | tencent}，默认 auto，沿 ADR-0030
 * 先例）：
 *
 * <ul>
 *   <li><b>auto</b>：东财失败（HTTP 错误/异常）<b>或空响应</b>（200 + data:null——封禁实测签名，与「盘外无数据」不可区分，
 *       降级腾讯两可：腾讯有数据即恢复、也无数据则仍 MISSING）→ 记 WARN → 腾讯按内部标的码（SH600519→sh600519）重拉；
 *       腾讯也失败才走既有降级（腾讯异常上抛，东财原因挂 {@code suppressed} 留诊断链）。东财恢复后每次仍先走东财，自动回归主选
 *   <li><b>eastmoney / tencent</b>：强制单源（排障用），语义同旧版——失败即按既有弹性语义降级
 * </ul>
 *
 * <p>腾讯路径产出<b>东财 f 键中间结构</b>（{@link TencentQuoteClient}，字段核对表见其 Javadoc），本类字段映射配置零改动； 降级轮 {@code
 * RawFetch.source} 标注「东方财富行情→腾讯备选」（强制 tencent 为「腾讯行情」）——健康徽章与日志可区分兜底轮， OK/MISSING/FAILED 三态契约不变。
 *
 * <p>弹性（既有语义不变）：超时/重试/熔断/缓存由 {@link AbstractSourceAdapter} 模板统一编排——auto 的「东财+腾讯」 合用同一
 * ResilienceSpec 预算（东财封禁失败为快速失败非挂起，预算内可完成两次调用；东财若挂起至超时，整次取数按既有超时降级）。 降级默认 MISSING——行情源挂不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 调研报告 §2.1/§4.1（🟢 实跑确认）。 f171 在报告 §4.1 落为振幅，但 §3.2 另注"含换手"，存在歧义；此处按 §4.1 映射矩阵落为
 * amplitude。东财 f 字段返回为字符串还是数值（fltt=2 下）亦待真实环境验证， {@link FieldMapper#toDecimal}/{@code toLong}
 * 对两者均兼容。
 */
public class QuoteSourceAdapter extends AbstractSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(QuoteSourceAdapter.class);

    /** subject.external_codes 中东财 secid 的键名。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** auto 降级轮来源标注（健康徽章可区分「东财失败但腾讯兜底成功」）。 */
    static final String TENCENT_FALLBACK_SUFFIX = "→腾讯备选";

    /** 强制 tencent 单源的来源标注。 */
    static final String TENCENT_ONLY_LABEL = "腾讯行情";

    private final EastMoneyClient client;
    private final TencentQuoteClient tencentClient;
    private final BackupSourceMode mode;
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
        this.mode = BackupSourceMode.parse(mode, "adapter.quote-source");
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
        return switch (mode) {
            case EASTMONEY -> fetchFromEastMoney(subject);
            case TENCENT -> fetchFromTencent(subject, TENCENT_ONLY_LABEL);
            case AUTO -> fetchAuto(subject);
        };
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
                "东财行情取数失败或空响应，auto 自动降级腾讯备选源重拉 subjectCode={} eastFailure={}",
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

    /** 东财路径（既有逻辑原样抽方法）：secid 缺失/盘外空 data → empty（→ MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject) throws Exception {
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

    /** 腾讯路径：按内部标的码换算符号拉取（不依赖 eastmoney secid），产出东财 f 键中间结构。 */
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
