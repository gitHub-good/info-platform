package com.info.platform.domain.valuation;

/**
 * 标的因子日快照行（{@code subject_factor_snapshot} 写入载荷，M20 方案 §4.1 + M21 T180 扩列）：五维因子分 + 总分/标签（T171
 * ScoreComposer 接管合成）+ 明细/flags/指纹 JSON + 计算时刻 + 最近事件日（M21 §4.1.6：三维护据事件最大日期，V31 ALTER 列—— 粗筛四键次级排序
 * + 榜单卡「最近事件」双用途）。created_at/updated_at 由仓储实现统一落。
 */
public record FactorSnapshotRow(
        long subjectId,
        String snapshotDate,
        double fCatalyst,
        double fConduction,
        double fFundamental,
        double fRisk,
        double fValuation,
        double totalScore,
        boolean breakthrough,
        String factorDetailJson,
        String dataFlagsJson,
        String weightBasis,
        String computedAtIso,
        String lastEventDate) {}
