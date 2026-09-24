package com.info.platform.application.aggregation;

import java.util.List;

/**
 * 标的池同步的全量列表源端口（T50，应用层定义、基础设施层实现——依赖倒置，先例 {@code DataSourceConfigFacade}）。
 *
 * <p>当前实现：{@code infrastructure.aggregation.RoutingSubjectListSource}（@Primary 路由，ADR-0030）——A 股桶按
 * {@code subject.sync.a-share-source} 在东财 {@code EastMoneyListClient}（ADR-0027 主选）与新浪 {@code
 * SinaSubjectListClient}（M7 备选源）间路由/自动降级，港股/指数桶恒东财。引擎只依赖本端口，增删备选源只动 infrastructure 一层，{@code
 * SubjectSyncService} 零改动。
 */
public interface SubjectListSource {

    /**
     * 拉取某市场桶的全量标的快照（分页遍历 + 礼貌限速 + total 完整性校验，全部在事务外完成）。
     *
     * @param bucket 市场桶（fs/市场/类型由桶定义绑定）
     * @return 全量快照（subjectCode 已去重；行数已与源 total 校验一致）
     * @throws IllegalStateException 拉取失败 / total 完整性不符 / 翻页超防御上限——调用方按「该市场整轮放弃」处理（零写入）
     */
    List<SubjectSnapshot> fetchAll(MarketSyncSpec bucket);
}
