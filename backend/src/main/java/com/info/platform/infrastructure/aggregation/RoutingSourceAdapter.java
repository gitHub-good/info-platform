package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.aggregation.SourceResult;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.RuntimeDataSource;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源热路由 adapter（T36 / ADR-0017 冲突解法 1）：每源唯一对外的 {@link SourceAdapter}。
 *
 * <p>原 mock/real 以 {@code adapter.mock.enabled} 启动期互斥装配（{@code @ConditionalOnProperty}），
 * 改为 14 个实现无条件装配为内部 bean + 本路由器<b>取数时按当前 {@code datasource.{CODE}} 配置分发</b>：
 *
 * <ul>
 *   <li>{@code enabled=false} → 直接返回 MISSING（不外调，聚合分区"暂无数据"，不阻断其他源）；
 *   <li>{@code mode=REAL} → 委派真实 adapter；{@code mode=MOCK} → 委派 mock adapter。
 * </ul>
 *
 * <p>配置经 {@link ConfigCenter} 内存快照用时读取——页面保存（写后换快照）下一次 {@link #fetch}
 * 即走新路由，无需重启（PRD 场景 3.2「切 mock 即生效」）。 {@code AggregationService} 等注入 {@code
 * List<SourceAdapter>} 的消费方只见本路由器（内部 adapter bean 已标记非自动装配候选），既有代码零改动。
 */
public final class RoutingSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RoutingSourceAdapter.class);

    private final SourceCode code;
    private final SourceAdapter real;
    private final SourceAdapter mock;
    private final ConfigCenter configCenter;

    public RoutingSourceAdapter(
            SourceCode code, SourceAdapter real, SourceAdapter mock, ConfigCenter configCenter) {
        this.code = code;
        this.real = real;
        this.mock = mock;
        this.configCenter = configCenter;
    }

    @Override
    public SourceResult fetch(Subject subject) {
        RuntimeDataSource config = configCenter.dataSource(code);
        if (!config.enabled()) {
            log.info("数据源已停用，跳过取数 sourceCode={} subjectId={}", code, subject.getId());
            return SourceResult.missing(code, subject.getId(), code.name());
        }
        return current(config).fetch(subject);
    }

    @Override
    public SourceCode sourceCode() {
        return code;
    }

    @Override
    public Set<SubjectType> supportedSubjectTypes() {
        // 以当前路由目标的声明为准（real/mock 对同一源声明一致；停用态由 fetch 短路，不影响类型过滤语义）
        return current(configCenter.dataSource(code)).supportedSubjectTypes();
    }

    /** 当前模式对应的目标 adapter（配置用时读取，热切换即换分支）。 */
    SourceAdapter current(RuntimeDataSource config) {
        return config.mode() == RuntimeDataSource.Mode.MOCK ? mock : real;
    }

    /** 当前真实 adapter（连通性测试按真实外呼口径使用，不受当前 mode 影响）。 */
    SourceAdapter realAdapter() {
        return real;
    }

    /** 当前 mock adapter（连通性测试在 mock 模式下做本地校验用）。 */
    SourceAdapter mockAdapter() {
        return mock;
    }
}
