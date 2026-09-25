package com.info.platform.domain.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * L0 noise 规则引擎（纯函数域服务，零 LLM，M15 T120，方案 §4.2 ①）。
 *
 * <p>关键词表（标题或摘要命中即 NOISE）+ 正则表（缺省 3 条：纯链接/短码帖、财富课程营销、平台自宣）。命中即隔离——NOISE 仅不进
 * L1/L2/热度/事件，资讯流照常可查（零误删兜底：规则过杀代价只是少归类，可接受且可观测）。
 *
 * <p>种子留档（缺省 15 词，开发按 13 源实情增删于此）：广告/推广/开户/开户礼/佣金万/礼包/课程/直播间的优惠/赞助/征文/订报/读者福利/招聘启事/有偿征稿/会员专享。
 * 规则参数可经 {@code pipeline.l0} 运行时配置热改（应用层 {@code PipelineSettings} 装配，缺省即本类常量）。
 */
public final class NoiseRuleEngine {

    /** 缺省关键词表（方案 §4.2：~15 词，按 13 源实情增删留档于此类注释）。 */
    static final List<String> DEFAULT_KEYWORDS =
            List.of(
                    "广告", "推广", "开户", "开户礼", "佣金万", "礼包", "课程", "直播间的优惠", "赞助", "征文", "订报", "读者福利",
                    "招聘启事", "有偿征稿", "会员专享");

    /** 缺省正则表（方案 §4.2 三条；命名进 l0_detail 便于过杀排查）。 */
    private static final List<NamedPattern> DEFAULT_PATTERNS =
            List.of(
                    new NamedPattern("纯链接短码帖", Pattern.compile("^https?://\\S+$")),
                    new NamedPattern("财富课程营销", Pattern.compile("(免费|限时).*(领取|报名|听课)")),
                    new NamedPattern("平台自宣", Pattern.compile("关注(本台|我们).*(公众号|频道)")));

    private final List<String> keywords;
    private final List<NamedPattern> patterns;

    public NoiseRuleEngine() {
        this(DEFAULT_KEYWORDS, DEFAULT_PATTERNS);
    }

    public NoiseRuleEngine(List<String> keywords, List<NamedPattern> patterns) {
        this.keywords = List.copyOf(keywords);
        this.patterns = List.copyOf(patterns);
    }

    /** 缺省规则实例（种子缺省，测试与装配共用）。 */
    public static NoiseRuleEngine withDefaults() {
        return new NoiseRuleEngine();
    }

    /**
     * 判定单条资讯是否 noise。
     *
     * <p>关键词多命中时取<b>最长</b>命中词（「开户礼」优先于「开户」——具体规则留痕可观测）；同长取种子序。 正则按声明序首命中。
     *
     * @param title 标题（null/空安全）
     * @param summary 摘要（null/空安全）
     * @return 命中规则名（{@code keyword:开户礼} / {@code pattern:平台自宣}，落 {@code l0_detail}）；未命中为空
     */
    public Optional<String> evaluate(String title, String summary) {
        Optional<String> keywordHit = firstKeywordHit(title);
        if (keywordHit.isEmpty()) {
            keywordHit = firstKeywordHit(summary);
        }
        if (keywordHit.isPresent()) {
            return keywordHit;
        }
        for (NamedPattern named : patterns) {
            if (matchesAny(named, title, summary)) {
                return Optional.of("pattern:" + named.name());
            }
        }
        return Optional.empty();
    }

    /** 关键词扫描（最长命中优先，具体规则进 l0_detail）；输入 null 安全。 */
    private Optional<String> firstKeywordHit(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return keywords.stream()
                .filter(text::contains)
                .max(Comparator.comparingInt(String::length))
                .map(hit -> "keyword:" + hit);
    }

    private static boolean matchesAny(NamedPattern named, String title, String summary) {
        if (title != null && !title.isBlank() && named.pattern().matcher(title).find()) {
            return true;
        }
        return summary != null && !summary.isBlank() && named.pattern().matcher(summary).find();
    }

    /** 正则条目（名称进 l0_detail，过杀可定位到具体规则）。 */
    public record NamedPattern(String name, Pattern pattern) {}
}
