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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 东方财富公告源真实 adapter（T05；ADR-0034 增巨潮备选降级——第五个降级链消费点）。
 *
 * <p>真实接入东财 {@code np-anotice-stock/api/security/ann}：按 {@code subject.external_codes} 派生的 6
 * 位证券代码取最新 N 条公告（标题/时间/分类/URL）， 经 {@link FieldMapper} 逐条字段映射后以 {@code data.items} 列表承载落 {@code
 * SourceResult.data}。 与 {@link MockAnnounceSourceAdapter} 经 {@link RoutingSourceAdapter}
 * 运行时路由共存（T36 热切换）。
 *
 * <h2>ADR-0034 · 降级链模型（复制 ADR-0031/0033 行情/估值/财务先例）</h2>
 *
 * <p>取数按 {@code datasource.ANNOUNCE.fallbackChain}（缺省 {@code ["eastmoney","cninfo"]}，注册表全链兜底）经
 * {@link FallbackChainRunner} 依次尝试：东财失败（HTTP 错误/异常）<b>或空响应</b>（ISSUE-A 实证东财财务/公告两端口同日 WAF 收紧）→ 记
 * WARN（带 provider 名与链位）→ 巨潮按 6 位代码重拉（orgId 表 24h 缓存 + POST 查询稳态单请求）；全链失败走既有弹性降级（末级异常上抛，前级挂 {@code
 * suppressed} 留诊断链），全链空响应 → MISSING。
 *
 * <ul>
 *   <li><b>DB 链优先</b>：{@code fallbackChain} 非空按序使用（可换主源/清备选，页面编辑）；空链 = 仅主源；缺省回落注册表全链。 本源<b>无旧
 *       {@code params.backupSource} 单值键历史</b>（ADR-0033 前公告源无备选开关），legacy 恒 null
 *   <li><b>热生效</b>：每次取数读快照现算（页面保存下一次取数即新链，无需重启）
 *   <li><b>来源标注</b>：{@code RawFetch.source} 标注实际命中 provider——链位 0「东方财富公告」/ 链位 1 「东方财富公告→巨潮资讯备选」/强制
 *       ["cninfo"] 单源时「巨潮资讯公告」（健康徽章与连通性测试可区分兜底轮）
 * </ul>
 *
 * <p>巨潮路径产出<b>东财公告 flat 中间结构</b>（{@link CninfoAnnounceClient}：art_code / title / notice_date /
 * stock_code / short_name / adjunct_url），经同一 {@link #itemMapping}（{@code eastmoney-announce.json}
 * 零改动）逐条映射；巨潮无东财 {@code column_name} 同款分类名 → {@code category} 白名单语义缺失不产出（ADR-0034 §3.2）； 详情 URL 由
 * {@code adjunct_url} 拼 {@code static.cninfo.com.cn} 直链（对齐东财路径「url 为构造项」策略）。
 *
 * <p>代码派生：公告 {@code stock_list} 参数用 6 位代码（如 {@code 600519}），非 secid。优先取 {@code eastmoney_code}
 * 键；缺省则从 {@code eastmoney} secid 按 {@code .} 切分派生（Spike-1 §5：secid 与纯代码可互相派生），兼容当前 V2 种子（仅存 secid）。
 * 两者皆缺 → MISSING（不发请求）。
 *
 * <p><b>列表型分区映射策略</b>（Spike-1 §4.4 末注）：{@link FieldMapper#map} 仅做单条 flat Map 映射，无法遍历列表或导航嵌套字段。 故本
 * adapter 在取数路径内： ① 对每条原始公告，拍平嵌套字段（{@code codes[0].stock_code}/{@code short_name}、{@code
 * columns[0].column_name}）为 flat 原始 map； ② 用 {@link #itemMapping}（{@code
 * field-mapping/eastmoney-announce.json}）逐条映射（{@code notice_date}→{@code to_iso_date} 等）； ③ 拼装详情
 * URL（东财路径由 {@code art_code}、巨潮路径由 {@code adjunct_url}——url 为「构造」项，非源字段映射）； ④ 收集为 {@code items}
 * 列表包装进 {@link RawFetch#data}（{@code Map.of("items", ...)}）。
 *
 * <p>{@link #mappingConfig} 返回 {@code items→items} passthrough：模板层 {@link
 * AbstractSourceAdapter#fetch} 的 {@code fieldMapper.map(data, mappingConfig)} 作用在<b>整张</b> data
 * map（单层），对已规范化的 {@code items} 列表原样透传进 {@code SourceResult.data["items"]}。 应用层聚合服务从 {@code
 * data.items} 键提取列表（同 {@link MockAnnounceSourceAdapter} 契约）。
 *
 * <p>弹性：超时 2s、重试 0（{@code ResilienceSpec.noRetry(2s)}，见 ADR-0011；ADR-0034 起两轮合用——东财一轮 +
 * 巨潮稳态单请求，冷启动首轮 orgId 拉取 +0.3s；东财挂起至超时则兜底不保证，沿 ADR-0031 已知限制）。 <b>取舍</b>：技术方案 §4.3 流程 1 原写公告「超时 2s
 * 重试 1」与 2s 页预算冲突； ResilienceRunner 不区分超时与连接失败，任一带重试方案（1s 重试 1 ≈ 2.2s / 2s 重试 1 ≈
 * 4s+）均突破预算。公告为幂等只读且非阻断分区，故以预算收敛优先，取 noRetry(2s)，最坏恰 2s、由聚合层 {@code orTimeout} 兜底。降级默认
 * MISSING——公告源挂不阻断详情页其他分区。
 *
 * <p>字段语义依据 Spike-1 §4.4 + 2026-09-21 curl 实测：{@code art_code}/{@code title}/{@code notice_date}
 * 为列表项顶层字段（实测确认）， {@code column_name}/{@code stock_code}/{@code short_name} 嵌在 {@code
 * columns[0]}/{@code codes[0]}（<b>与 §4.4 平铺假设不符</b>，本 adapter 按实测拍平）。
 */
public class AnnounceSourceAdapter extends AbstractSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AnnounceSourceAdapter.class);

    /** subject.external_codes 中东财 6 位代码的键名（优先取，Spike-1 §5 建议两者都存）。 */
    private static final String EASTMONEY_CODE_KEY = "eastmoney_code";

    /** subject.external_codes 中东财 secid 的键名（派生 6 位代码的回退来源，V2 种子用此键）。 */
    private static final String EASTMONEY_SECID_KEY = "eastmoney";

    /** 降级链的运行时键（ADR-0033 写路径统一；DB 链优先，缺省回落注册表全链）。 */
    static final String CHAIN_CONFIG_KEY = "datasource.ANNOUNCE.fallbackChain";

    /** 该源可用 provider 注册表（代码事实，首元素 = 默认主源）。 */
    private static final List<SourceProvider> PROVIDERS =
            SourceProviders.providers(SourceCode.ANNOUNCE);

    /** 业务名（来源标注与日志的链位名词：「东方财富公告」）。 */
    private static final String SOURCE_NOUN = "公告";

    /**
     * 模板层 map 步骤用：整张 data map（{@code {"items":[...]}}）的 items 列表原样透传。 逐条字段映射在取数路径内用 {@link
     * #itemMapping} 完成。
     */
    private static final List<FieldMapping> ITEMS_PASSTHROUGH =
            List.of(new FieldMapping("items", "items", Transform.NONE));

    private final EastMoneyAnnounceClient client;
    private final CninfoAnnounceClient cninfoClient;
    private final FieldMapper fieldMapper;
    private final List<FieldMapping> itemMapping;

    public AnnounceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyAnnounceClient client,
            CninfoAnnounceClient cninfoClient) {
        super(cache, fieldMapper, runner, breaker);
        this.client = client;
        this.cninfoClient = cninfoClient;
        // 模板层 fieldMapper 为 private，子类需另持引用以在取数路径内逐条映射（Spike-1 §4.4 列表场景）。
        this.fieldMapper = fieldMapper;
        this.itemMapping = fieldMapper.loadMapping("field-mapping/eastmoney-announce.json");
    }

    @Override
    public SourceCode sourceCode() {
        return SourceCode.ANNOUNCE;
    }

    /** T31：np-anotice-stock 为上市公司公告端点，本源仅支持股票。 */
    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        return EnumSet.of(SubjectType.STOCK);
    }

    @Override
    protected String sourceLabel() {
        return "东方财富公告";
    }

    @Override
    protected List<FieldMapping> mappingConfig() {
        return ITEMS_PASSTHROUGH;
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
            Subject subject, SourceProvider provider, String label) {
        return switch (provider) {
            case EASTMONEY -> fetchFromEastMoney(subject, label);
            case CNINFO -> fetchFromCninfo(subject, label);
            default -> throw new IllegalArgumentException("公告源未接入 provider: " + provider);
        };
    }

    /**
     * 当前降级链（ADR-0033 热读）：每次取数读 {@code datasource.ANNOUNCE} 快照现算（页面保存即热生效）—— DB {@code
     * fallbackChain} 优先，缺省回落注册表全链兜底。 本源无旧 {@code params.backupSource} 单值键历史（ADR-0033
     * 前公告源无备选开关），legacy 恒传 null；DB 手改坏值（链成员越界）不阻断取数——WARN 后回落全链（读取侧兜底）。
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

    /** 东财路径（既有逻辑，label 按链位标注）：空列表（无公告/代码不存在）→ empty（→ 尝试下一级 / MISSING）。 */
    private Optional<RawFetch> fetchFromEastMoney(Subject subject, String label) {
        String stockCode = resolveStockCode(subject);
        if (stockCode == null) {
            return Optional.empty();
        }
        Optional<List<Map<String, Object>>> rawList = client.fetchAnnouncements(stockCode);
        if (rawList.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> items = new ArrayList<>(rawList.get().size());
        for (Map<String, Object> raw : rawList.get()) {
            Map<String, Object> flat = flatten(raw);
            Map<String, Object> item = mappedItem(flat);
            Object artCode = flat.get("art_code");
            if (artCode != null) {
                // url 为构造项（Spike-1 §4.4），非源字段映射，由 art_code 拼详情 PDF 直链。
                item.put("url", client.detailUrlOf(String.valueOf(artCode)));
            }
            items.add(Collections.unmodifiableMap(item));
        }
        return Optional.of(itemsFetch(items, label));
    }

    /** 巨潮路径：client 已输出东财 flat 条目 → 同一 itemMapping 逐条映射 → adjunct_url 拼详情直链。 */
    private Optional<RawFetch> fetchFromCninfo(Subject subject, String label) {
        String stockCode = resolveStockCode(subject);
        if (stockCode == null) {
            return Optional.empty();
        }
        Optional<List<Map<String, Object>>> rawList = cninfoClient.fetchAnnouncements(stockCode);
        if (rawList.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> items = new ArrayList<>(rawList.get().size());
        for (Map<String, Object> flat : rawList.get()) {
            Map<String, Object> item = mappedItem(flat);
            Object adjunctUrl = flat.get("adjunct_url");
            if (adjunctUrl != null) {
                // url 为构造项：巨潮 adjunctUrl 相对路径拼 static.cninfo.com.cn 直链（ADR-0034）。
                item.put("url", cninfoClient.detailUrlOf(String.valueOf(adjunctUrl)));
            }
            items.add(Collections.unmodifiableMap(item));
        }
        return Optional.of(itemsFetch(items, label));
    }

    private static RawFetch itemsFetch(List<Map<String, Object>> items, String label) {
        return new RawFetch(Map.of("items", List.copyOf(items)), label, Instant.now());
    }

    /** 单条 flat 原始 map → 规范化条目（{@code eastmoney-announce.json} 逐条映射，两 provider 路径共用）。 */
    private Map<String, Object> mappedItem(Map<String, Object> flat) {
        return new LinkedHashMap<>(fieldMapper.map(flat, itemMapping));
    }

    /**
     * 拍平单条公告的嵌套字段为 flat 原始 map 供 {@link FieldMapper} 单层映射。
     *
     * <p>顶层取 {@code art_code}/{@code title}/{@code notice_date}（实测为列表项直接字段）； 嵌套取 {@code
     * codes[0].stock_code}/ {@code short_name}、{@code columns[0].column_name}（Spike-1 §4.4
     * 误列为顶层，实测嵌在数组）。 缺失/null 字段不入 flat（FieldMapper 白名单语义下不产出目标字段）。
     */
    private static Map<String, Object> flatten(Map<String, Object> raw) {
        Map<String, Object> flat = new LinkedHashMap<>();
        putIfPresent(flat, "art_code", raw.get("art_code"));
        putIfPresent(flat, "title", raw.get("title"));
        putIfPresent(flat, "notice_date", raw.get("notice_date"));
        Object codes = raw.get("codes");
        if (codes instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            putIfPresent(flat, "stock_code", first.get("stock_code"));
            putIfPresent(flat, "short_name", first.get("short_name"));
        }
        Object columns = raw.get("columns");
        if (columns instanceof List<?> list
                && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first) {
            putIfPresent(flat, "column_name", first.get("column_name"));
        }
        return flat;
    }

    private static void putIfPresent(Map<String, Object> flat, String key, Object value) {
        if (value != null) {
            flat.put(key, value);
        }
    }

    /**
     * 取东财 6 位证券代码：优先 {@code eastmoney_code} 键；缺省从 {@code eastmoney} secid（如 {@code 1.600519}）按首个
     * {@code .} 切分派生为 {@code 600519}。 两者皆缺/空 → null（→ MISSING，不发 HTTP）。
     */
    private String resolveStockCode(Subject subject) {
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
