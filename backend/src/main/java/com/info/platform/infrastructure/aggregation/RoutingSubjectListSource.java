package com.info.platform.infrastructure.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.aggregation.SubjectSyncConfigValidator;
import com.info.platform.domain.aggregation.FallbackChains;
import com.info.platform.domain.aggregation.SourceProvider;
import com.info.platform.domain.aggregation.SourceProviders;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 列表源路由（M7 备选源切换，ADR-0030；ADR-0033 升级降级链模型）：{@link SubjectListSource} 的 {@code @Primary} 组合实现——引擎
 * {@code SubjectSyncService} 仍只依赖端口，多源选择收拢在本类。
 *
 * <p>路由规则（运行时键 {@code subject.sync}，<b>每次取数用时读快照</b>——页面保存后下一轮同步即新源，无需重启）：
 *
 * <ul>
 *   <li><b>非 A 股桶（港股/指数）恒东财</b>——新浪无港股节点（实测 hk_stocks 返空，ADR-0027/0030），链只作用于 A 股桶
 *   <li><b>降级链</b>（ADR-0033，文档字段 {@code fallbackChain: ["eastmoney","sina"]}，缺省注册表全链）：
 *       按链依次尝试——主源拉取失败（重试耗尽 / total 不符等）→ 记 WARN（带 provider 名与链位）→ 下一备选
 *       <b>整桶重拉</b>（非增量续传，全量语义下无半截数据）；全链失败抛末级异常（前级挂 {@code suppressed} 留诊断链）
 *   <li><b>旧开关折算</b>（{@code aShareSource: auto | eastmoney | sina}，读取兼容逐步淘汰）：auto→全链、
 *       单值→单元素链（强制单源，排障用）
 * </ul>
 *
 * <p>键缺失/配置中心缺失回落构造期缺省（生产装配取 {@link DataSourceDefaults#A_SHARE_LIST_SOURCE}）；DB 手改坏值
 * （链成员越界/旧开关非法）不阻断同步——WARN 后回落全链（写路径有校验，读取侧兜底）。
 */
@Primary
@Component
public class RoutingSubjectListSource implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingSubjectListSource.class);

    /** A 股桶可用 provider 注册表（代码事实，首元素 = 默认主源；港股/指数桶恒东财与此无关）。 */
    private static final List<SourceProvider> PROVIDERS = SourceProviders.A_SHARE_LIST_PROVIDERS;

    /** A 股桶源选择模式（{@code subject.sync.aShareSource} 合法取值；ADR-0033 起为兼容口径）。 */
    enum Mode {
        AUTO,
        EASTMONEY,
        SINA
    }

    private final EastMoneyListClient eastMoney;
    private final SinaSubjectListClient sina;

    /** 开关回落值（纯构造单测/配置中心缺失或键缺失时）。 */
    private final Mode fallbackMode;

    /** 配置中心（ADR-0032 热化）：每次取数读 {@code subject.sync} 快照；null（纯构造单测）回落构造期缺省。 */
    private final ConfigCenter configCenter;

    /** Spring 装配构造：开关回落缺省取 {@link DataSourceDefaults}，热读注入配置中心。 */
    @Autowired
    public RoutingSubjectListSource(
            EastMoneyListClient eastMoney, SinaSubjectListClient sina, ConfigCenter configCenter) {
        this(eastMoney, sina, DataSourceDefaults.A_SHARE_LIST_SOURCE, configCenter);
    }

    /** 全参构造（纯构造单测指定回落模式；configCenter 传 null 即固定构造期模式）。 */
    public RoutingSubjectListSource(
            EastMoneyListClient eastMoney,
            SinaSubjectListClient sina,
            String mode,
            ConfigCenter configCenter) {
        this.eastMoney = eastMoney;
        this.sina = sina;
        this.fallbackMode = parseMode(mode);
        this.configCenter = configCenter;
    }

    /** 固定模式构造（纯构造单测：无配置中心，模式即构造期值）。 */
    public RoutingSubjectListSource(
            EastMoneyListClient eastMoney, SinaSubjectListClient sina, String mode) {
        this(eastMoney, sina, mode, null);
    }

    /** 解析配置值：大小写不敏感；空值回落 auto；非法取值抛 {@link IllegalArgumentException}（fail-fast）。 */
    static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> Mode.AUTO;
            case "eastmoney" -> Mode.EASTMONEY;
            case "sina" -> Mode.SINA;
            default -> throw new IllegalArgumentException(
                    "subject.sync.aShareSource 非法取值: '" + raw + "'（合法: auto | eastmoney | sina）");
        };
    }

    @Override
    public List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket) {
        if (bucket != MarketSyncSpec.A_SHARE_STOCK) {
            // 港股/指数桶恒东财（新浪无港股节点，ADR-0030）——链只作用于 A 股桶
            return eastMoney.fetchAll(bucket);
        }
        return fetchByChain(bucket, currentChain());
    }

    /** 按降级链依次整桶拉取：每级失败 WARN 留痕带 provider 名；全链失败抛末级异常（前级挂 suppressed）。 */
    private List<SubjectSnapshot> fetchByChain(MarketSyncSpec bucket, List<SourceProvider> chain) {
        RuntimeException lastFailure = null;
        for (int index = 0; index < chain.size(); index++) {
            SourceProvider provider = chain.get(index);
            try {
                return fetchByProvider(bucket, provider);
            } catch (RuntimeException failure) {
                if (lastFailure != null) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
                log.warn(
                        "A 股桶列表源拉取失败（provider={}，链位 {}/{}）：{} → 按降级链尝试下一备选整桶重拉",
                        provider.code(),
                        index + 1,
                        chain.size(),
                        failure.toString());
            }
        }
        if (lastFailure == null) {
            // 空链（理论不可达——resolve 保证至少主源）：按默认主源直呼，保留失败语义
            return eastMoney.fetchAll(bucket);
        }
        throw lastFailure;
    }

    /** 单 provider 整桶拉取（链已按注册表校验/兜底，枚举穷举无未知分支）。 */
    private List<SubjectSnapshot> fetchByProvider(MarketSyncSpec bucket, SourceProvider provider) {
        return switch (provider) {
            case EASTMONEY -> eastMoney.fetchAll(bucket);
            case SINA -> sina.fetchAll(bucket);
            default -> throw new IllegalArgumentException("A 股列表源未接入 provider: " + provider);
        };
    }

    /**
     * 当前 A 股桶降级链（ADR-0033 热读）：每次取数读 {@code subject.sync} 快照现算（页面保存即热生效）—— 文档 {@code fallbackChain}
     * 优先，缺省按旧 {@code aShareSource} 折算，再缺回落注册表全链兜底。 键缺失/配置中心缺失回落构造期缺省；坏值 WARN 回落全链（写路径校验兜底）。
     */
    List<SourceProvider> currentChain() {
        if (configCenter == null) {
            return legacyChain(fallbackMode);
        }
        JsonNode doc = configCenter.document(SubjectSyncConfigValidator.KEY).orElse(null);
        if (doc == null) {
            return legacyChain(fallbackMode);
        }
        List<String> rawChain = rawChainOf(doc);
        if (rawChain != null
                && !rawChain.isEmpty()
                && !FallbackChains.isValidChain(rawChain, PROVIDERS)) {
            log.warn("A 股桶降级链配置非法，回落全链 key=subject.sync.fallbackChain raw={}", rawChain);
            rawChain = null;
        }
        String legacy = doc.path("aShareSource").asText(null);
        if (!FallbackChains.isValidLegacy(legacy, PROVIDERS)) {
            log.warn("A 股桶列表源开关取值非法，回落全链 key=subject.sync.aShareSource raw='{}'", legacy);
            legacy = null;
        }
        return FallbackChains.resolve(rawChain, legacy, PROVIDERS);
    }

    /** {@code subject.sync.fallbackChain} 原始值（缺失/非数组 → null = 未配置；非文本成员按 asText 归一，交合法性判断拦截）。 */
    private static List<String> rawChainOf(JsonNode doc) {
        JsonNode node = doc.path("fallbackChain");
        if (!node.isArray()) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        node.forEach(item -> chain.add(item.asText()));
        return List.copyOf(chain);
    }

    /** 构造期开关 → 链（纯构造场景与键缺失回落）。 */
    private static List<SourceProvider> legacyChain(Mode mode) {
        return switch (mode) {
            case EASTMONEY -> List.of(SourceProvider.EASTMONEY);
            case SINA -> List.of(SourceProvider.SINA);
            case AUTO -> PROVIDERS;
        };
    }
}
