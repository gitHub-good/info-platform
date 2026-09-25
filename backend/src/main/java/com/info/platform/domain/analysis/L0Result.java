package com.info.platform.domain.analysis;

/**
 * L0 规则预筛结果（{@code news_analysis.l0_result} 持久化为枚举名文本，M15 T120，ADR-0046 裁决 1）。
 *
 * <p>领域层纯净枚举（仅 JDK）。语义对齐方案 §4.2：
 *
 * <ul>
 *   <li>{@link #PASS}：正常条目，进入 L1 归类与后续层级。
 *   <li>{@link #NOISE}：广告/推广噪音（关键词或正则命中），隔离不进 L1/L2/热度/事件——资讯流照常可查（零误删兜底）。
 *   <li>{@link #NEAR_DUP}：近重复（simhash 海明 + 归一化编辑距离双段确认），关联主条（{@code near_dup_of}）不进 L1。
 * </ul>
 */
public enum L0Result {
    /** 正常条目（进 L1）。 */
    PASS,
    /** 广告噪音（隔离，不进 L1/L2/热度/事件；资讯流可查）。 */
    NOISE,
    /** 近重复（关联 24h 窗口内 PASS 主条，不进 L1）。 */
    NEAR_DUP;

    /** 从持久化文本反查（未知值抛 {@code IllegalArgumentException}——迁移白名单校验）。 */
    public static L0Result fromName(String name) {
        if (name != null) {
            for (L0Result result : values()) {
                if (result.name().equals(name)) {
                    return result;
                }
            }
        }
        throw new IllegalArgumentException("未知 l0_result: " + name);
    }
}
