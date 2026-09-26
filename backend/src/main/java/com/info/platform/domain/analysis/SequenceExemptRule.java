package com.info.platform.domain.analysis;

import java.util.regex.Pattern;

/**
 * 序列豁免谓词（BUG-03 修复，M16 T130，方案 §4.2 / ADR-0051 注记）：模板化序列条目永不标记 NEAR_DUP。
 *
 * <p>BUG-03 实证（M15 测试报告 P2）：CPI 月度序列 19 条被近重复双段判定误并——{@code external_id} 形如 {@code
 * RPT_ECONOMY_CPI#2026-08-01}（数据源对模板化序列条目的显式分卷编号），标题「CPI：2026年08月份 同比 0.8%」逐月仅数字 不同。两类强序列信号满足任一即豁免：
 *
 * <ol>
 *   <li>{@code externalId} 含 {@code #}（序列分卷标记）；
 *   <li>标题匹配「YYYY年X月份」月度模式（CPI/PPI/LPR 等宏观月度数据标题）。
 * </ol>
 *
 * <p>豁免语义：条目照常建行（PASS）并正常参与 24h 比较池作主条候选——真同稿可引用之；只是自身<b>永不作为被并方</b>标记 NEAR_DUP。留痕：l0_detail =
 * {@code seq-exempt:#} / {@code seq-exempt:monthly}（可查可统计）。
 *
 * <p>回归红线：既有真同稿合并语义零变化（豁免只作用于两类强序列信号，普通近重复照并）。
 */
public final class SequenceExemptRule {

    /** externalId 序列分卷标记（数据源显式分卷编号的分隔符）。 */
    static final String VOLUME_MARK = "#";

    /** 月度标题模式：「(19|20)\d{2}年(0?[1-9]|1[0-2])月份」——年 1900~2099，月 1~12 带可选前导零。 */
    private static final Pattern MONTHLY_TITLE_PATTERN =
            Pattern.compile("(19|20)\\d{2}年(0?[1-9]|1[0-2])月份");

    /** l0_detail 留痕 token（方案 §4.2：seq-exempt:# / seq-exempt:monthly）。 */
    public static final String DETAIL_VOLUME = "seq-exempt:#";

    public static final String DETAIL_MONTHLY = "seq-exempt:monthly";

    private SequenceExemptRule() {}

    /**
     * 豁免判定（纯函数）。
     *
     * @param externalId news_item.external_id（可空）
     * @param title 标题（可空）
     * @return 豁免留痕 token；不豁免返回 null
     */
    public static String exemptToken(String externalId, String title) {
        if (externalId != null && externalId.contains(VOLUME_MARK)) {
            return DETAIL_VOLUME;
        }
        if (title != null && MONTHLY_TITLE_PATTERN.matcher(title).find()) {
            return DETAIL_MONTHLY;
        }
        return null;
    }

    /** 是否豁免（布尔视图，供流程分支）。 */
    public static boolean isExempt(String externalId, String title) {
        return exemptToken(externalId, title) != null;
    }

    /** 月度标题模式（单测直测边界月值用）。 */
    static Pattern monthlyTitlePattern() {
        return MONTHLY_TITLE_PATTERN;
    }
}
