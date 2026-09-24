package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import com.info.platform.application.aggregation.SubjectSyncConfigValidator;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.DataSourceDefaults;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 列表源路由（M7 备选源切换，ADR-0030）：{@link SubjectListSource} 的 {@code @Primary} 组合实现——引擎 {@code
 * SubjectSyncService} 仍只依赖端口，多源选择收拢在本类。
 *
 * <p>路由规则（运行时键 {@code subject.sync.aShareSource: auto | eastmoney | sina}，缺省 auto；原 yml {@code
 * subject.sync.a-share-source} 启动期绑定，<b>ADR-0032 起改每次取数用时读快照</b>——页面保存后下一轮同步即新源，无需重启）：
 *
 * <ul>
 *   <li><b>非 A 股桶（港股/指数）恒东财</b>——新浪无港股节点（实测 hk_stocks 返空，ADR-0027/0030）
 *   <li><b>auto</b>：东财 A 股桶拉取失败（重试耗尽 / total 不符等）→ 记 WARN → 自动降级新浪<b>整桶重拉</b>（非增量续传，
 *       全量语义下无半截数据）；新浪也失败才抛桶失败（东财原因挂 {@code suppressed} 留诊断链）
 *   <li><b>eastmoney / sina</b>：强制单源（排障用——验证降级逻辑是否为故障源），失败即桶失败
 * </ul>
 *
 * <p>键缺失/配置中心缺失回落构造期缺省（生产装配取 {@link DataSourceDefaults#A_SHARE_LIST_SOURCE}）；DB 手改坏值不阻断同步—— WARN
 * 后回落 auto（写路径有 oneOf 校验，读取侧兜底）。
 */
@Primary
@Component
public class RoutingSubjectListSource implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingSubjectListSource.class);

    /** A 股桶源选择模式（{@code subject.sync.aShareSource} 合法取值）。 */
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
            // 港股/指数桶恒东财（新浪无港股节点，ADR-0030）——模式只作用于 A 股桶
            return eastMoney.fetchAll(bucket);
        }
        return switch (currentMode()) {
            case EASTMONEY -> eastMoney.fetchAll(bucket);
            case SINA -> sina.fetchAll(bucket);
            case AUTO -> fetchAuto(bucket);
        };
    }

    /**
     * 当前 A 股桶源选择（ADR-0032 热读）：每次取数读 {@code subject.sync.aShareSource} 快照（页面保存即热生效）。
     * 键缺失/配置中心缺失回落构造期缺省；坏值 WARN 回落 auto（写路径 oneOf 校验兜底）。
     */
    Mode currentMode() {
        if (configCenter == null) {
            return fallbackMode;
        }
        String raw =
                configCenter
                        .document(SubjectSyncConfigValidator.KEY)
                        .map(doc -> doc.path("aShareSource").asText(null))
                        .orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallbackMode;
        }
        try {
            return parseMode(raw);
        } catch (IllegalArgumentException e) {
            log.warn("A 股桶列表源开关取值非法，回落 auto key=subject.sync.aShareSource raw='{}'", raw);
            return Mode.AUTO;
        }
    }

    /** auto：东财失败 → WARN 留痕 → 新浪整桶重拉；双失败抛新浪异常（东财原因挂 suppressed）。 */
    private List<SubjectSnapshot> fetchAuto(MarketSyncSpec bucket) {
        try {
            return eastMoney.fetchAll(bucket);
        } catch (RuntimeException eastFailure) {
            log.warn("A 股桶东财列表源拉取失败，自动降级新浪备选源整桶重拉（ADR-0030 auto）: {}", eastFailure.toString());
            try {
                return sina.fetchAll(bucket);
            } catch (RuntimeException sinaFailure) {
                sinaFailure.addSuppressed(eastFailure);
                throw sinaFailure;
            }
        }
    }
}
