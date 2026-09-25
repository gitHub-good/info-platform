package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ImportanceScorer 单测（T122，方案 §4.4 打分公式逐项）：源权重 / 强中触发词 ×2.0/×1.0 / 标的加成计一次 / 阈值命中边界 / 未知源类别回落 1.0 /
 * 同词多现不重复计分 / 缺省参数（种子同源）。AAA 结构。
 */
class ImportanceScorerTest {

    private final ImportanceScorer.ScorerParams params = ImportanceScorer.defaults();

    @Test
    void score_sourceWeight_byCategory() {
        // Arrange + Act + Assert：政策/宏观 2.0 · 快讯 1.5 · 媒体/国际/自建 1.0（无触发词纯源权重基线）
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "政策", false, params))
                .isCloseTo(2.0, within(1e-9));
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "宏观", false, params))
                .isCloseTo(2.0, within(1e-9));
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "快讯", false, params))
                .isCloseTo(1.5, within(1e-9));
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "媒体", false, params))
                .isCloseTo(1.0, within(1e-9));
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "国际", false, params))
                .isCloseTo(1.0, within(1e-9));
        assertThat(ImportanceScorer.score("普通标题", "普通摘要", "自建", false, params))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void score_unknownCategory_fallsBackToWeightOne() {
        assertThat(ImportanceScorer.score("标题", "摘要", "未知类别", false, params))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    void score_strongTrigger_worthTwoEach() {
        // 标题命中「业绩预告」+ 摘要命中「并购」→ 快讯 1.5 + 2×2.0 = 5.5
        double score = ImportanceScorer.score("某公司业绩预告", "同时披露并购计划", "快讯", false, params);
        assertThat(score).isCloseTo(5.5, within(1e-9));
    }

    @Test
    void score_mediumTrigger_worthOneEach() {
        // 「签约」+「投产」两中触发 → 媒体 1.0 + 2×1.0 = 3.0
        double score = ImportanceScorer.score("公司与地方政府签约", "新产线投产", "媒体", false, params);
        assertThat(score).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void score_strongAndMediumCombined() {
        // 「回购」（强）+「涨价」（中）→ 自建 1.0 + 2.0 + 1.0 = 4.0
        double score = ImportanceScorer.score("公司公告回购股份", "产品全线涨价", "自建", false, params);
        assertThat(score).isCloseTo(4.0, within(1e-9));
    }

    @Test
    void score_subjectBonus_countedOnce() {
        // 标的池命中加成 1.5 计一次（无论几家公司命中——布尔入参语义）
        double withSubject = ImportanceScorer.score("贵州茅台公告", "无触发词内容", "媒体", true, params);
        assertThat(withSubject).isCloseTo(1.0 + 1.5, within(1e-9));
    }

    @Test
    void score_sameTriggerRepeated_notDoubleCounted() {
        // 「降准」在标题与摘要各出现一次 → 只计一次（规则信号去重）
        double once = ImportanceScorer.score("央行降准", "降准释放流动性", "宏观", false, params);
        double twiceOnlyOnce = ImportanceScorer.score("央行降准", "无关内容", "宏观", false, params);
        assertThat(once).isCloseTo(twiceOnlyOnce, within(1e-9));
    }

    @Test
    void score_nullTitleOrSummary_tolerated() {
        assertThat(ImportanceScorer.score(null, null, "快讯", false, params))
                .isCloseTo(1.5, within(1e-9));
    }

    @Test
    void hitsThreshold_boundaryInclusive() {
        // 缺省阈值 2.5：恰等于命中（≥），略低不命中
        assertThat(ImportanceScorer.hitsThreshold(2.5, params)).isTrue();
        assertThat(ImportanceScorer.hitsThreshold(2.49, params)).isFalse();
        assertThat(params.threshold()).isCloseTo(2.5, within(1e-9));
    }

    @Test
    void defaults_seedAlignedTriggerLists() {
        // 缺省参数与 pipeline.l2 种子同源：强 19 词 / 中 15 词 / 源权重 6 类 / 加成 1.5
        assertThat(params.strongTriggers())
                .containsExactly(
                        "业绩预告", "预增", "预亏", "并购", "重组", "收购", "回购", "增持", "减持", "重大合同", "中标", "处罚",
                        "立案", "降准", "降息", "关税", "管制", "突破", "获批");
        assertThat(params.mediumTriggers())
                .containsExactly(
                        "签约", "合作", "投产", "上调", "下调", "涨价", "降价", "新高", "新低", "停牌", "复牌", "辞职",
                        "聘任", "上线", "发布");
        assertThat(params.sourceWeights())
                .containsAllEntriesOf(
                        Map.of("政策", 2.0, "宏观", 2.0, "快讯", 1.5, "媒体", 1.0, "国际", 1.0, "自建", 1.0));
        assertThat(params.subjectBonus()).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void score_customParams_respected() {
        // 自定义参数（页面热改后）：阈值 4.0 / 加成 3.0 / 自定触发词
        ImportanceScorer.ScorerParams custom =
                new ImportanceScorer.ScorerParams(
                        Map.of("快讯", 1.0), List.of("涨停"), List.of(), 3.0, 4.0);
        double score = ImportanceScorer.score("某股涨停", "", "快讯", true, custom);
        assertThat(score).isCloseTo(1.0 + 2.0 + 3.0, within(1e-9));
        assertThat(ImportanceScorer.hitsThreshold(score, custom)).isTrue();
    }
}
