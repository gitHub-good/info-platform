package com.info.platform.domain.aggregation;

import java.util.Set;

/**
 * 数据源适配器端口（依赖倒置：领域层定义、基础设施层实现）。
 *
 * <p>每源一个实现（T03~T08）。基础设施层 {@code AbstractSourceAdapter} 提供模板方法，编排缓存命中、 弹性（超时/重试/熔断）、字段映射与降级；子类只实现
 * doFetch 与映射配置。本接口保持领域层纯净，不依赖任何框架类型。
 *
 * <p>对齐技术方案 §4.4「统一数据访问层」与 ADR-0003。
 */
public interface SourceAdapter {

    /**
     * 取某标的在本源的数据。
     *
     * <p>结果含状态（OK/MISSING/FAILED）：单源缺失/异常不抛出，由调用方（应用层聚合服务）按 sourceStatus 分区兜底， 不阻断其他源。缓存命中直接返回。
     */
    SourceResult fetch(Subject subject);

    /**
     * 分区子端点分页取数（M12 详情分区分页，ADR-0037 决策 2）：「看更多」翻页路径， <b>绕过 SourceCache 快照缓存直调源</b>（每请求恰好一次外呼，
     * 首屏聚合缓存不动）。
     *
     * <p>三态语义与 {@link #fetch} 同骨架同口径（基础设施层 {@code AbstractSourceAdapter#runGuarded}：熔断→弹性→事件旁路→降级，
     * 不读写缓存）。默认不支持分页——支持翻页的源（公告/新闻/事件）显式覆写，其余源调用即抛 {@link
     * UnsupportedOperationException}（fail-fast，不静默退化为第一页）。
     *
     * @param subject 标的（含外部代码映射）
     * @param page 页码（≥1；公告端点另有 5 页产品上限，由接口层校验）
     * @param size 页大小（新闻源忽略——源页大小是运维配置 {@code newsPageSize}，不属调用方自由度）
     */
    default SourceResult fetchPage(Subject subject, int page, int size) {
        throw new UnsupportedOperationException("源不支持分页取数: " + sourceCode());
    }

    /** 本源标识（六类之一），用作缓存/限频/熔断分区键。 */
    SourceCode sourceCode();

    /**
     * 本源支持的标的类型集合（T31 扩展位：类型→源映射的注册点）。
     *
     * <p>默认 = 全部已开放类型（股票/指数/板块）；预留类型（基金/债券）须由对应数据接入落地时显式声明。 聚合层据此跳过不适用类型的取数——不调外部源，分区按 missing
     * 降级（sourceStatus 契约不变）。 「股票专用」的源（如财务/估值/公告）应覆写收窄到 {@code EnumSet.of(SubjectType.STOCK)}。
     *
     * <p>新增一种标的类型的全部注册动作：① {@code SubjectType} 开放该枚举值；② 各源 adapter 按需覆写本方法声明支持；③ 提供 external_codes
     * 映射种子。核心聚合/推送/订阅代码零改动。
     */
    default Set<SubjectType> supportedSubjectTypes() {
        return SubjectType.activeTypes();
    }
}
