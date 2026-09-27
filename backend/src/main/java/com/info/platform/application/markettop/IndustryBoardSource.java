package com.info.platform.application.markettop;

import java.util.List;

/**
 * 东财 datacenter 行业板块源端口（M21 T180，方案 §4.1.2 通道 A）：{@code RPT_WEB_RESPREDICT.INDUSTRY_BOARD} 全量分页拉取。
 *
 * <p>端口在应用层、实现在基础设施层（{@code
 * infrastructure.aggregation.EastmoneyDatacenterClient}——SubjectListSource 同款依赖 倒置）：回填编排只依赖端口，换源（v2
 * 东财板块组件接口）只换实现类，{@link IndustryMemberBackfillService} 零改动。
 */
public interface IndustryBoardSource {

    /**
     * 全量拉取机构覆盖标的的行业板块行（确定性兜底通道——实测 2933 只 / 30 页稳定）。
     *
     * @return 6 位证券代码 + 东财板块名（板块原文，SW 映射在读侧 IndustryDirectory.swPrimaryOf）
     * @throws java.lang.IllegalStateException 拉取失败/翻页完整性校验失败（调用方降级继续，不阻塞漏斗）
     */
    List<IndustryBoardRow> fetchIndustryBoards();

    /** 单行：6 位证券代码（非 secid、无市场前缀）+ 东财板块名。 */
    record IndustryBoardRow(String securityCode, String industryBoard) {}
}
