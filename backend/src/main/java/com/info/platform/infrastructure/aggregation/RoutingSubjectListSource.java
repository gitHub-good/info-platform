package com.info.platform.infrastructure.aggregation;

import com.info.platform.application.aggregation.MarketSyncSpec;
import com.info.platform.application.aggregation.SubjectListSource;
import com.info.platform.application.aggregation.SubjectSnapshot;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 列表源路由（M7 备选源切换，ADR-0030）：{@link SubjectListSource} 的 {@code @Primary} 组合实现——引擎 {@code
 * SubjectSyncService} 仍只依赖端口，多源选择收拢在本类。
 *
 * <p>路由规则（{@code subject.sync.a-share-source: auto | eastmoney | sina}，默认 auto）：
 *
 * <ul>
 *   <li><b>非 A 股桶（港股/指数）恒东财</b>——新浪无港股节点（实测 hk_stocks 返空，ADR-0027/0030）
 *   <li><b>auto</b>：东财 A 股桶拉取失败（重试耗尽 / total 不符等）→ 记 WARN → 自动降级新浪<b>整桶重拉</b>（非增量续传，
 *       全量语义下无半截数据）；新浪也失败才抛桶失败（东财原因挂 {@code suppressed} 留诊断链）
 *   <li><b>eastmoney / sina</b>：强制单源（排障用——验证降级逻辑是否为故障源），失败即桶失败
 * </ul>
 *
 * <p>配置笔误启动即失败（fail-fast）；空值容错回落 auto（语义同 {@code @Value} 缺省）。
 */
@Primary
@Component
public class RoutingSubjectListSource implements SubjectListSource {

    private static final Logger log = LoggerFactory.getLogger(RoutingSubjectListSource.class);

    /** A 股桶源选择模式（{@code subject.sync.a-share-source} 合法取值）。 */
    enum Mode {
        AUTO,
        EASTMONEY,
        SINA
    }

    private final EastMoneyListClient eastMoney;
    private final SinaSubjectListClient sina;
    private final Mode mode;

    public RoutingSubjectListSource(
            EastMoneyListClient eastMoney,
            SinaSubjectListClient sina,
            @Value("${subject.sync.a-share-source:auto}") String mode) {
        this.eastMoney = eastMoney;
        this.sina = sina;
        this.mode = parseMode(mode);
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
                    "subject.sync.a-share-source 非法取值: '" + raw + "'（合法: auto | eastmoney | sina）");
        };
    }

    @Override
    public List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket) {
        if (bucket != MarketSyncSpec.A_SHARE_STOCK) {
            // 港股/指数桶恒东财（新浪无港股节点，ADR-0030）——模式只作用于 A 股桶
            return eastMoney.fetchAll(bucket);
        }
        return switch (mode) {
            case EASTMONEY -> eastMoney.fetchAll(bucket);
            case SINA -> sina.fetchAll(bucket);
            case AUTO -> fetchAuto(bucket);
        };
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
