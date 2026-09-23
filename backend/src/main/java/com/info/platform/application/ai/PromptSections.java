package com.info.platform.application.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板分段与占位符共享工具（应用层，T45 自 {@code PromptTemplateService} 等价提取，方案 §4.8）。
 *
 * <p>分段标记与占位符解析规则此前只在渲染器（{@code PromptTemplateService.render}）存在，M5 保存校验器需要同一套
 * 切分/提取逻辑作判定基准——提取为共享工具后，「渲染怎么切」与「校验怎么判」物理上是一份代码（校验器与渲染器单一事实源）。
 *
 * <h2>规则（与既有渲染行为逐字一致，既有单测守护等价重构）</h2>
 *
 * <ul>
 *   <li>分段：独占行的 {@code ---SYSTEM---} / {@code ---USER---} 标记，system 在前 user 在后；缺失或顺序错视为数据损坏 抛
 *       {@link IllegalStateException}。
 *   <li>占位符：双花括号 {@code {{key}}}（键名仅 {@code \w+}，允许前后空白），不触碰单花括号 JSON 形状 {@code {summary,...}}。
 * </ul>
 */
public final class PromptSections {

    /** system 段标记（独占行）。 */
    public static final String SYSTEM_MARKER = "---SYSTEM---";

    /** user 段标记（独占行）。 */
    public static final String USER_MARKER = "---USER---";

    /** 占位符 {@code {{key}}}（键名仅字母数字下划线，允许前后空白）。 */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    private PromptSections() {}

    /**
     * 切分 system/user 两段正文（不含标记行）。
     *
     * @param rawTemplate 模板原文
     * @param version 版本号（仅用于异常信息定位）
     * @return {@code [system, user]} 两段
     * @throws IllegalStateException 缺任一分段标记或顺序颠倒（与渲染器同款条件）
     */
    public static String[] split(String rawTemplate, String version) {
        int sysIdx = rawTemplate.indexOf(SYSTEM_MARKER);
        int userIdx = rawTemplate.indexOf(USER_MARKER);
        if (sysIdx < 0 || userIdx < 0 || userIdx <= sysIdx) {
            throw new IllegalStateException(
                    "提示词模板缺少 "
                            + SYSTEM_MARKER
                            + "/"
                            + USER_MARKER
                            + " 分段标记或顺序错误: version="
                            + version);
        }
        String system = rawTemplate.substring(sysIdx + SYSTEM_MARKER.length(), userIdx);
        String user = rawTemplate.substring(userIdx + USER_MARKER.length());
        return new String[] {system, user};
    }

    /**
     * 提取占位符键名（按出现序去重）。
     *
     * @param text 任意文本（整段模板或单段）
     * @return 键名列表（LinkedHashSet 保序去重）；无占位符为空列表
     */
    public static List<String> extractKeys(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return List.copyOf(keys);
    }

    /** 占位符键数（去重后），列表接口 {@code placeholderCount} 与详情 {@code placeholders} 的计数口径。 */
    public static int countKeys(String text) {
        return extractKeys(text).size();
    }
}
