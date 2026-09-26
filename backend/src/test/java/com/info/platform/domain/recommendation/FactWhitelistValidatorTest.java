package com.info.platform.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.recommendation.FactWhitelistValidator.RejectReason;
import com.info.platform.domain.recommendation.FactWhitelistValidator.Result;
import com.info.platform.domain.recommendation.FactWhitelistValidator.Whitelist;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 事实白名单校验器单测（T132，方案 §3.3 裁决 3 / §4.5）：四类违规矩阵（虚构标的 / 越界行业 / 编造数字 / 方向反转）+ 长度护栏
 * + 各类合法通过 + 数字抽取归一（%/小数/负号）。零新增事实红线（REQ 抽检 100%）的机制化防线——任一违规即拒走模板兜底。
 */
class FactWhitelistValidatorTest {

    private static final Set<String> POOL =
            Set.of("贵州茅台", "宁德时代", "五粮液", "中芯国际", "长江电力", "a");

    private static Whitelist whitelist(Direction direction) {
        return new Whitelist(
                Set.of("贵州茅台"),
                Set.of("食品饮料"),
                Set.of(new BigDecimal("80"), new BigDecimal("0.8"), new BigDecimal("30")),
                direction);
    }

    private static Result validate(String chain, Direction direction, Set<String> pool) {
        return FactWhitelistValidator.validate(chain, whitelist(direction), pool);
    }

    private static Result validate(String chain, Direction direction) {
        return validate(chain, direction, POOL);
    }

    @Test
    void pass_whenChainRestatesWhitelistedFacts() {
        // Arrange：只重述输入事实（允许标的/允许行业/允许数字/方向一致/长度合规）
        String chain = "贵州茅台净利润同比增长80%，利好食品饮料行业，直接利好你关注的标的。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void reject_subject_inPoolButNotAllowed() {
        // Arrange：虚构标的——文本出现池内「宁德时代」但 ∉ 允许标的名集
        String chain = "宁德时代净利润同比增长80%，利好食品饮料行业。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.SUBJECT_NOT_ALLOWED);
    }

    @Test
    void pass_subject_outsidePool_notJudged() {
        // Arrange：池外未知词不判（只拦「换了/加了标的」不拦普通词汇——池外公司名不在守备面）
        String chain = "某虚拟公司净利润同比增长80%，利好食品饮料行业。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isTrue();
    }

    @Test
    void reject_industry_directoryWordNotAllowed() {
        // Arrange：越界行业——文本出现申万行业名「医药生物」但 ∉ 允许行业集
        String chain = "贵州茅台净利润同比增长80%，利好医药生物行业。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.INDUSTRY_NOT_ALLOWED);
    }

    @Test
    void reject_industry_aliasWordNotAllowed_andPass_whenIndustryAllowed() {
        // Arrange：别名目录词「半导体」→ 电子；电子 ∉ 允许集 → 拒； ∈ 允许集 → 过
        String chain = "贵州茅台产品通过半导体验证，带动收入增长80%。";

        Result rejected = validate(chain, Direction.BULLISH);

        Whitelist allowedElectronics =
                new Whitelist(
                        Set.of("贵州茅台"),
                        Set.of("食品饮料", "电子"),
                        Set.of(new BigDecimal("80")),
                        Direction.BULLISH);
        Result passed = FactWhitelistValidator.validate(chain, allowedElectronics, POOL);

        // Assert
        assertThat(rejected.valid()).isFalse();
        assertThat(rejected.reason()).isEqualTo(RejectReason.INDUSTRY_NOT_ALLOWED);
        assertThat(passed.valid()).isTrue();
    }

    @Test
    void reject_number_notInStructuredSet() {
        // Arrange：编造数字——文本「90%」∉ 结构化数字集 {80, 0.8, 30}
        String chain = "贵州茅台净利润同比增长90%，利好食品饮料行业。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NUMBER_NOT_ALLOWED);
    }

    @Test
    void pass_number_afterNormalization() {
        // Arrange：数字归一——「80.0%」「+80%」与白名单 80 同值（BigDecimal 去尾零）
        String chain = "贵州茅台净利润同比增长80.0%，环比+80%几乎持平，约30元。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isTrue();
    }

    @Test
    void reject_directionWord_conflictsWithEventDirection() {
        // Arrange：方向反转——BULLISH 事件文本出现利空系词「利空」/「下跌」
        String chain = "贵州茅台净利润同比增长80%，短期利空情绪释放。";

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.DIRECTION_CONFLICT);
    }

    @Test
    void reject_directionWord_oppositeDirection() {
        // Arrange：BEARISH 事件文本出现利好系词「利好」
        String chain = "贵州茅台销量下滑30%，仍被解读为利好。";

        // Act
        Result result = validate(chain, Direction.BEARISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.DIRECTION_CONFLICT);
    }

    @Test
    void reject_neutralEvent_withStrongDirectionWord() {
        // Arrange：NEUTRAL 卡出现强方向词（利好/利空系任一）→ 拒
        String chain = "贵州茅台发布经营数据，净利润同比增长80%，整体利好。";

        // Act
        Result result = validate(chain, Direction.NEUTRAL);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.DIRECTION_CONFLICT);
    }

    @Test
    void reject_logicChainExceedsMaxLength() {
        // Arrange：120 字长度护栏（超长 → 拒走模板）
        String chain = "长".repeat(FactWhitelistValidator.LOGIC_CHAIN_MAX_LENGTH + 1);

        // Act
        Result result = validate(chain, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.LENGTH_EXCEEDED);
    }

    @Test
    void pass_logicChainAtMaxLength() {
        // Arrange：恰好 120 字（边界含）
        String filler = "贵".repeat(FactWhitelistValidator.LOGIC_CHAIN_MAX_LENGTH);

        // Act
        Result result = validate(filler, Direction.BULLISH);

        // Assert
        assertThat(result.valid()).isTrue();
    }

    @Test
    void numbersIn_extractsPercentDecimalAndNegative() {
        // Arrange：数字串抽取（含 %/小数/负号/逗号千分位）；期望值同规去尾零（80 → 8E+1）
        String text = "同比增长+80%，环比-0.8%，营收1,200亿元，目标价30元";

        // Act
        Set<BigDecimal> numbers = FactWhitelistValidator.numbersIn(text);

        // Assert
        assertThat(numbers)
                .containsExactlyInAnyOrder(
                        new BigDecimal("80").stripTrailingZeros(),
                        new BigDecimal("-0.8"),
                        new BigDecimal("1"),
                        new BigDecimal("200").stripTrailingZeros(),
                        new BigDecimal("30").stripTrailingZeros());
    }

    @Test
    void numbersIn_blankOrNullTexts_yieldEmpty() {
        assertThat(FactWhitelistValidator.numbersIn((String) null)).isEmpty();
        assertThat(FactWhitelistValidator.numbersIn("", "   ")).isEmpty();
    }

    @Test
    void numbersIn_multipleTexts_union() {
        // Arrange：key_figures 值 + title + summary 多文本并集口径（期望值同规去尾零）
        Set<BigDecimal> numbers = FactWhitelistValidator.numbersIn("+80%", "上半年营收翻2倍");

        // Assert
        assertThat(numbers)
                .containsExactlyInAnyOrder(
                        new BigDecimal("80").stripTrailingZeros(), new BigDecimal("2"));
    }
}
