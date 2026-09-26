package com.info.platform.domain.recommendation;

import com.info.platform.domain.analysis.Direction;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实白名单校验器（领域纯函数，M16 T132，ADR-0051 裁决 3 / 方案 §3.3/§4.5）：对 LLM 输出的 logicChain 做
 * <b>四类白名单集合比对 + 长度护栏</b>，任一违规即拒（调用方同步切模板拼接，不重试 LLM——时效优先）。
 *
 * <p>零新增事实红线（REQ「逻辑链三环节全部来自结构化事实」抽检 100%）的机制化防线——幻觉防线不建立在提示词自觉上：
 *
 * <ol>
 *   <li><b>标的</b>：文本出现<b>标的池内</b>公司名（名长 ≥2 contains）但 ∉ 允许标的名集（卡片标的区 ∪ event.subjects 名）→
 *       拒（池外未知词不判——只拦「换了/加了标的」不拦普通词汇）。
 *   <li><b>行业</b>：文本出现申万行业名或 {@link IndustryDirectory} 别名目录词（词长 ≥2）但所属行业 ∉ 允许行业集
 *       （event.affected_industries ∪ 关联命中行业）→ 拒。单字目录词（铜/铝）不作文本命中判据——contains 误拦面大于收益。
 *   <li><b>数字</b>：正则抽取文本数字串（含 %/小数/负号/千分位逗号拆分），任一数值 ∉ 结构化数字集
 *       （key_figures[].value ∪ quote/title/summary 数字，{@link #numbersIn}）→ 拒。
 *   <li><b>方向</b>：方向词表命中方向 ≠ event.direction → 拒；NEUTRAL 卡出现强方向词（利好/利空系任一）→ 拒。
 *   <li><b>长度护栏</b>：logicChain &gt; {@link #LOGIC_CHAIN_MAX_LENGTH} 字 → 拒（走模板）。
 * </ol>
 */
public final class FactWhitelistValidator {

    /** 逻辑链长度护栏（方案 §3.3 ⑤：120 字，超长走模板）。 */
    public static final int LOGIC_CHAIN_MAX_LENGTH = 120;

    /** 标的名参与 contains 判定的最小长度（设计 §3.3 ①：名长 ≥2——单字名误拦面过大）。 */
    private static final int MIN_NAME_LENGTH = 2;

    /** 数字串抽取正则（可选符号 + 整数/小数；千分位逗号自然拆为两段各自比对）。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?[0-9]+(?:\\.[0-9]+)?");

    /** 方向词表——利好系（BULLISH 事件文本命中不冲突；BEARISH/NEUTRAL 事件命中即拒）。 */
    private static final Set<String> BULLISH_WORDS =
            Set.of("利好", "上涨", "偏多", "提振", "走强", "攀升");

    /** 方向词表——利空系（BEARISH 事件文本命中不冲突；BULLISH/NEUTRAL 事件命中即拒）。 */
    private static final Set<String> BEARISH_WORDS =
            Set.of("利空", "下跌", "偏空", "承压", "走弱", "下滑");

    private FactWhitelistValidator() {}

    /**
     * 四类白名单 + 长度校验（判定顺序固定：长度 → 标的 → 行业 → 数字 → 方向；首违即返，理由可观测）。
     *
     * @param logicChain LLM 输出的逻辑链文本（null/空白直接拒——解析失败面）
     * @param whitelist 允许集（标的名/行业/数字 + 事件方向）
     * @param subjectPoolNames 标的池全量名（①类判定的检测词表，仅用于「是公司名」的识别）
     * @return 校验结果（valid + 首违理由）
     */
    public static Result validate(
            String logicChain, Whitelist whitelist, Collection<String> subjectPoolNames) {
        if (logicChain == null || logicChain.isBlank()) {
            return Result.reject(RejectReason.LENGTH_EXCEEDED);
        }
        if (logicChain.length() > LOGIC_CHAIN_MAX_LENGTH) {
            return Result.reject(RejectReason.LENGTH_EXCEEDED);
        }
        if (violatesSubject(logicChain, whitelist.allowedSubjectNames(), subjectPoolNames)) {
            return Result.reject(RejectReason.SUBJECT_NOT_ALLOWED);
        }
        if (violatesIndustry(logicChain, whitelist.allowedIndustries())) {
            return Result.reject(RejectReason.INDUSTRY_NOT_ALLOWED);
        }
        if (violatesNumber(logicChain, whitelist.allowedNumbers())) {
            return Result.reject(RejectReason.NUMBER_NOT_ALLOWED);
        }
        if (violatesDirection(logicChain, whitelist.direction())) {
            return Result.reject(RejectReason.DIRECTION_CONFLICT);
        }
        return Result.ok();
    }

    /** 抽取多段文本的全部数字串并归一（去尾零 BigDecimal；+80%/80.0%/80 同值——允许集与文本抽取同源同规）。 */
    public static Set<BigDecimal> numbersIn(String... texts) {
        Set<BigDecimal> numbers = new LinkedHashSet<>();
        if (texts == null) {
            return numbers;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Matcher matcher = NUMBER_PATTERN.matcher(text);
            while (matcher.find()) {
                numbers.add(normalized(matcher.group()));
            }
        }
        return numbers;
    }

    private static boolean violatesSubject(
            String text, Set<String> allowedNames, Collection<String> poolNames) {
        if (poolNames == null) {
            return false;
        }
        for (String name : poolNames) {
            if (name == null || name.trim().length() < MIN_NAME_LENGTH) {
                continue;
            }
            String trimmed = name.trim();
            if (text.contains(trimmed) && !allowedNames.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean violatesIndustry(String text, Set<String> allowedIndustries) {
        for (String word : IndustryDirectory.allWords()) {
            // 单字目录词（铜/铝/锂）contains 误拦面过大：行业名全为双字以上，别名仅取 ≥2 字词参与文本判定
            if (word.length() < MIN_NAME_LENGTH || !text.contains(word)) {
                continue;
            }
            String industry = IndustryDirectory.industryOfWord(word);
            if (industry != null && !allowedIndustries.contains(industry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean violatesNumber(String text, Set<BigDecimal> allowedNumbers) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            if (!allowedNumbers.contains(normalized(matcher.group()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean violatesDirection(String text, Direction direction) {
        boolean bullish = containsAny(text, BULLISH_WORDS);
        boolean bearish = containsAny(text, BEARISH_WORDS);
        return switch (direction) {
            case BULLISH -> bearish;
            case BEARISH -> bullish;
            case NEUTRAL -> bullish || bearish; // NEUTRAL 卡出现强方向词即拒
        };
    }

    private static boolean containsAny(String text, Set<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal normalized(String token) {
        return new BigDecimal(token).stripTrailingZeros();
    }

    /** 允许集（快照 input：标的名集 / 行业集 / 数字集 / 事件方向；数字集在构造期统一去尾零——与文本抽取同规）。 */
    public record Whitelist(
            Set<String> allowedSubjectNames,
            Set<String> allowedIndustries,
            Set<BigDecimal> allowedNumbers,
            Direction direction) {

        public Whitelist {
            allowedSubjectNames = Set.copyOf(allowedSubjectNames == null ? Set.of() : allowedSubjectNames);
            allowedIndustries = Set.copyOf(allowedIndustries == null ? Set.of() : allowedIndustries);
            Set<BigDecimal> normalized = new LinkedHashSet<>();
            if (allowedNumbers != null) {
                for (BigDecimal number : allowedNumbers) {
                    if (number != null) {
                        normalized.add(number.stripTrailingZeros());
                    }
                }
            }
            allowedNumbers = Set.copyOf(normalized);
        }
    }

    /** 拒绝理由（首违可观测，日志与 JobRunStats 明细消费）。 */
    public enum RejectReason {
        LENGTH_EXCEEDED,
        SUBJECT_NOT_ALLOWED,
        INDUSTRY_NOT_ALLOWED,
        NUMBER_NOT_ALLOWED,
        DIRECTION_CONFLICT
    }

    /** 校验结果（valid=true 通过；valid=false 时 reason 为首违理由）。 */
    public record Result(boolean valid, RejectReason reason) {

        static Result ok() {
            return new Result(true, null);
        }

        static Result reject(RejectReason reason) {
            return new Result(false, reason);
        }
    }
}
