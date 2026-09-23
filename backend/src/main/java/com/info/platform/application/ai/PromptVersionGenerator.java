package com.info.platform.application.ai;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提示词模板版本号生成器（应用层纯函数，T45 / ADR-0021）：服务端受控生成 {@code v{major}.{minor}}，用户不可自填。
 *
 * <p>解析既有版本取 (major, minor) 数值 max：MINOR（默认）= (maxMajor, maxMinor+1)，如 {@code v1.9 → v1.10}； MAJOR
 * = (maxMajor+1, 0)，如 {@code v1.9 → v2.0}。非法格式（手工改库产物，如 {@code v1.0.1}）忽略并记 WARN， 不参与 max
 * 计算——不破坏运行，仅无法成为自动生成的基数。场景无任何合法版本（防御）从 {@code v1.0} 起。
 *
 * <p>真实版本序由 {@link #compareNumeric} 在应用层数值比较表达（TEXT 字典序在 v1.9/v1.10 类场景错序， {@code
 * findActiveByBriefType} 的 {@code ORDER BY version DESC} 仅是唯一激活不变量被破坏时的防御兜底）。
 */
public final class PromptVersionGenerator {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionGenerator.class);

    /** 合法版本格式：{@code v{major}.{minor}}（两段式非负整数）。 */
    static final Pattern VERSION_FORMAT = Pattern.compile("^v(\\d+)\\.(\\d+)$");

    /** 兜底起步版本（场景无任何合法版本行时）。 */
    static final String FIRST_VERSION = "v1.0";

    private PromptVersionGenerator() {}

    /** 版本升级策略：MINOR 次版本 +1（默认，措辞调优）/ MAJOR 主版本 +1 归零（大改）。 */
    public enum VersionStrategy {
        MINOR,
        MAJOR
    }

    /**
     * 生成下一个版本号。
     *
     * @param existingVersions 该场景全部既有版本号（含置废）
     * @param strategy 升级策略（null 按 MINOR）
     * @return 新版本号；既有集合为空（或全部非法）返回 {@code v1.0}
     */
    public static String nextVersion(
            Collection<String> existingVersions, VersionStrategy strategy) {
        List<int[]> parsed =
                existingVersions == null
                        ? List.of()
                        : existingVersions.stream()
                                .map(PromptVersionGenerator::parseOrNull)
                                .filter(v -> v != null)
                                .toList();
        if (parsed.isEmpty()) {
            return FIRST_VERSION;
        }
        int[] max = parsed.stream().max(PromptVersionGenerator::compareParts).orElseThrow();
        VersionStrategy bump = strategy == null ? VersionStrategy.MINOR : strategy;
        return bump == VersionStrategy.MAJOR
                ? "v" + (max[0] + 1) + ".0"
                : "v" + max[0] + "." + (max[1] + 1);
    }

    /**
     * 数值版本比较（列表展示序与"最新版"判定用）。
     *
     * <p>非法格式（无两段式解析结果）退化为字符串比较，保证全序稳定不抛异常。
     */
    public static int compareNumeric(String left, String right) {
        int[] l = parseOrNull(left);
        int[] r = parseOrNull(right);
        if (l != null && r != null) {
            return compareParts(l, r);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static int compareParts(int[] a, int[] b) {
        int byMajor = Integer.compare(a[0], b[0]);
        return byMajor != 0 ? byMajor : Integer.compare(a[1], b[1]);
    }

    /** 解析 {@code v{major}.{minor}}；非法格式记 WARN 返回 null（不参与 max 计算）。 */
    private static int[] parseOrNull(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = VERSION_FORMAT.matcher(version.trim());
        if (!m.matches()) {
            log.warn("提示词版本号非两段式格式，忽略: {}", version);
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            // 超长数字串防御（理论上 \d+ 可超 int 上限）
            log.warn("提示词版本号数值溢出，忽略: {}", version);
            return null;
        }
    }

    /** 便捷比较器（versions 列表降序排序用）。 */
    public static Comparator<String> descending() {
        return PromptVersionGenerator::compareNumeric;
    }
}
