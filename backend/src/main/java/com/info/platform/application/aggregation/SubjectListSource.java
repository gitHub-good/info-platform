package com.info.platform.application.aggregation;

import java.util.List;

/**
 * 标的池同步的全量列表源端口（T50，应用层定义、基础设施层实现——依赖倒置，先例 {@code DataSourceConfigFacade}）。
 *
 * <p>当前实现：{@code infrastructure.aggregation.EastMoneyListClient}（东财 push2 clist，ADR-0027 主选）。
 * 连续多日失败切备选源时只换实现类，{@code SubjectSyncService} 引擎零改动。
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
