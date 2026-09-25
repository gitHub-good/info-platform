package com.info.platform.application.aggregation;

/**
 * 分区子端点页大小运行时设置端口（M12 T90，REQ-20260925-09）。
 *
 * <p>公告子端点页大小缺省（{@code datasource.ANNOUNCE.params.announcePageSize}，M12 起 10）与新闻源页大小
 * （{@code datasource.NEWS.params.newsPageSize}，20）。与 {@link AggregationRuntimeSettings} 同款端口倒置： 应用层直接依赖
 * ConfigCenter（基础设施）会与 infrastructure→application 依赖成环（LayeredArchitectureTest 层切片无环约束）， 故走端口由基础设施层实现。
 * 消费点<b>每请求读取</b>即热生效（LIVE 级，页面保存下一次翻页即新页大小）。
 */
public interface SectionPageSettings {

    /** 公告分区子端点页大小缺省（size 缺席时采用；显式传值 1~50 由接口层校验）。 */
    int announcePageSize();

    /** 新闻源页大小（子端点不接受 size——源页大小是运维配置；size 回显与 hasMore 口径共用本值）。 */
    int newsPageSize();
}
