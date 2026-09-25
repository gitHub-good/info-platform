package com.info.platform.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * NoiseRuleEngine 单测（T120，方案 §4.2 ① / §6 L0 组）：关键词命中（标题/摘要两入口）、三条正则、干净财经标题放行、空值安全、 自定义规则注入。AAA
 * 结构，纯函数零依赖。
 */
class NoiseRuleEngineTest {

    private final NoiseRuleEngine engine = NoiseRuleEngine.withDefaults();

    @Test
    void evaluate_keywordHitInTitle_returnsNoise() {
        // Act：标题含「开户礼」
        Optional<String> hit = engine.evaluate("新户开户礼：佣金万1.01 免五元", null);

        // Assert：命中并留痕规则名
        assertThat(hit).contains("keyword:开户礼");
    }

    @Test
    void evaluate_keywordHitInSummary_returnsNoise() {
        // Act：标题干净、摘要命中「读者福利」
        Optional<String> hit = engine.evaluate("今日市场收评", "本报告为读者福利专享内容");

        assertThat(hit).contains("keyword:读者福利");
    }

    @Test
    void evaluate_keywordFirstMatchWins_bySeedOrder() {
        // Act：同时含「广告」与「推广」——种子表顺序取首命中（可观测确定性）
        Optional<String> hit = engine.evaluate("广告合作推广洽谈", null);

        assertThat(hit).contains("keyword:广告");
    }

    @Test
    void evaluate_pureLinkTitle_matchesPattern() {
        Optional<String> hit = engine.evaluate("https://example.com/short/abc123", null);

        assertThat(hit).contains("pattern:纯链接短码帖");
    }

    @Test
    void evaluate_marketingCourse_matchesPattern() {
        Optional<String> hit = engine.evaluate("限时免费报名量化训练营", null);

        assertThat(hit).contains("pattern:财富课程营销");
    }

    @Test
    void evaluate_platformSelfPromo_matchesPattern() {
        Optional<String> hit = engine.evaluate("收盘了，关注我们的公众号获取更多资讯", null);

        assertThat(hit).contains("pattern:平台自宣");
    }

    @Test
    void evaluate_cleanFinanceTitle_passes() {
        // 正常财经资讯不误杀：含「开户」是噪音词，但央行「降准」类标题必须放行
        assertThat(engine.evaluate("央行宣布下调存款准备金率0.5个百分点", "释放长期资金约1万亿元")).isEmpty();
        assertThat(engine.evaluate("贵州茅台发布2026年半年度业绩预告", null)).isEmpty();
        assertThat(engine.evaluate(null, "锂电池排产持续回暖，上游材料价格企稳")).isEmpty();
    }

    @Test
    void evaluate_blankInputs_safeEmpty() {
        assertThat(engine.evaluate(null, null)).isEmpty();
        assertThat(engine.evaluate("", "")).isEmpty();
        assertThat(engine.evaluate("  ", "  ")).isEmpty();
    }

    @Test
    void evaluate_customRules_injected() {
        // 自定义规则（pipeline.l0 热改承载）：空关键词表 + 单正则
        NoiseRuleEngine custom =
                new NoiseRuleEngine(
                        List.of(),
                        List.of(
                                new NoiseRuleEngine.NamedPattern(
                                        "测试规则", java.util.regex.Pattern.compile("测试噪音"))));

        assertThat(custom.evaluate("这条是测试噪音内容", null)).contains("pattern:测试规则");
        // 缺省关键词（如「广告」）在自定义实例中不生效
        assertThat(custom.evaluate("广告合作", null)).isEmpty();
    }

    @Test
    void defaults_carryFifteenKeywords() {
        // 种子留档：缺省关键词 15 词（方案 §4.2 ~15 词）
        assertThat(NoiseRuleEngine.DEFAULT_KEYWORDS).hasSize(15);
    }
}
