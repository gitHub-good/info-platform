package com.info.platform.application.ai;

import com.info.platform.domain.ai.PlaceholderDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 提示词模板保存校验器（应用层纯函数，T45，方案 §4.3 规则表 V1~V5）——后端为权威校验，前端镜像规则仅体验级预检。
 *
 * <p>三级分级：
 *
 * <ol>
 *   <li>硬拦截（V1/V2/V3）：分段标记缺失或顺序错（与渲染器 {@link PromptSections#split} 同款条件）、system 段缺 "json" 字样（V8
 *       播种不变量，DeepSeek JSON mode 前提）、空模板/超 64KB 上限 → {@code hardErrors} 逐条文案。
 *   <li>确认拦截（V4）：相对底稿移除的占位符 → {@code removed} 清单（携带注册表 description）；放行条件 = 移除键全部出现在请求 {@code
 *       confirmedRemovedKeys}（无状态确认，服务端每次重算 diff，改底稿后旧确认自动失效）。
 *   <li>警告级（V5）：注册表之外的未知占位符 → {@code unknown} 清单；不参与放行判定，仅回显（对齐 PRD 「仅警告/不阻断」；场景 2 休眠的事件占位符如实落入此类）。
 * </ol>
 *
 * <p>V4 基准 = 请求 {@code baseVersionId} 指向的底稿版本行（与前端编辑底稿一致才不会确认清单错位，UI 联判点 3）； 基准解析与回落（缺省/无效 →
 * 当前激活版，无激活版 → 空集）在 {@code PromptTemplateAdminService} 编排，本类只做纯计算。
 */
public final class PromptTemplateValidator {

    /** 模板长度上限（V3 防御性：SQLite TEXT 无压力，防误贴全量数据）。 */
    static final int MAX_TEMPLATE_LENGTH = 65536;

    /** removed 项查不到注册表说明时的占位文案（方案 §4.3 V4）。 */
    static final String NO_SOURCE_DESCRIPTION = "（无来源说明）";

    private PromptTemplateValidator() {}

    /**
     * 校验新模板文本。
     *
     * @param newTemplate 新模板正文（整段原文）
     * @param baseTemplate 底稿正文（diff 基准；null/空 = 基准空集，removed 恒空）
     * @param registeredPlaceholders 该场景注册表描述符（V5 基准，与对照区同源）
     * @return 三级结果（hardErrors / removed / unknown）
     */
    public static ValidationResult validate(
            String newTemplate,
            String baseTemplate,
            List<PlaceholderDescriptor> registeredPlaceholders) {
        List<String> hardErrors = new ArrayList<>();
        if (newTemplate == null || newTemplate.isBlank()) {
            hardErrors.add("模板不能为空");
            // 空模板无段可切、无占位符可 diff，直接返回（removed/unknown 恒空）
            return new ValidationResult(List.copyOf(hardErrors), List.of(), List.of());
        }
        if (newTemplate.length() > MAX_TEMPLATE_LENGTH) {
            hardErrors.add("模板长度超过上限 " + MAX_TEMPLATE_LENGTH + " 字符");
        }
        // V1：分段标记存在且 SYSTEM 在 USER 前（与渲染器同款条件，但不抛异常而是结构化逐条文案）
        boolean systemPresent = newTemplate.contains(PromptSections.SYSTEM_MARKER);
        boolean userPresent = newTemplate.contains(PromptSections.USER_MARKER);
        if (!systemPresent) {
            hardErrors.add("缺少 " + PromptSections.SYSTEM_MARKER + " 分段标记");
        }
        if (!userPresent) {
            hardErrors.add("缺少 " + PromptSections.USER_MARKER + " 分段标记");
        }
        // V2：system 段含 "json" 字样（大小写不敏感；仅标记齐全可切分时判定）
        if (systemPresent && userPresent) {
            int sysIdx = newTemplate.indexOf(PromptSections.SYSTEM_MARKER);
            int userIdx = newTemplate.indexOf(PromptSections.USER_MARKER);
            if (userIdx <= sysIdx) {
                hardErrors.add(
                        "分段标记顺序错误: "
                                + PromptSections.SYSTEM_MARKER
                                + " 须在 "
                                + PromptSections.USER_MARKER
                                + " 之前");
            } else {
                String system =
                        newTemplate.substring(
                                sysIdx + PromptSections.SYSTEM_MARKER.length(), userIdx);
                if (!system.toLowerCase().contains("json")) {
                    hardErrors.add("system 段须含 json 字样（JSON 输出模式前提）");
                }
            }
        }
        return new ValidationResult(
                List.copyOf(hardErrors),
                removedItems(newTemplate, baseTemplate, registeredPlaceholders),
                unknownItems(newTemplate, registeredPlaceholders));
    }

    /** V4：{@code removed = placeholders(base) − placeholders(new)}，携带注册表 description（查不到则占位文案）。 */
    private static List<PlaceholderItem> removedItems(
            String newTemplate, String baseTemplate, List<PlaceholderDescriptor> registered) {
        if (baseTemplate == null || baseTemplate.isBlank()) {
            return List.of();
        }
        Set<String> newKeys = new LinkedHashSet<>(PromptSections.extractKeys(newTemplate));
        Map<String, PlaceholderDescriptor> registry =
                registered.stream()
                        .collect(
                                Collectors.toMap(
                                        PlaceholderDescriptor::key,
                                        Function.identity(),
                                        (a, b) -> a));
        return PromptSections.extractKeys(baseTemplate).stream()
                .filter(key -> !newKeys.contains(key))
                .map(
                        key -> {
                            PlaceholderDescriptor d = registry.get(key);
                            return new PlaceholderItem(
                                    key, d != null ? d.description() : NO_SOURCE_DESCRIPTION);
                        })
                .toList();
    }

    /**
     * V5：{@code unknown = placeholders(new) − registry(briefType)}（description 恒 null——不在注册表即无说明）。
     */
    private static List<PlaceholderItem> unknownItems(
            String newTemplate, List<PlaceholderDescriptor> registered) {
        Set<String> registeredKeys =
                registered.stream().map(PlaceholderDescriptor::key).collect(Collectors.toSet());
        return PromptSections.extractKeys(newTemplate).stream()
                .filter(key -> !registeredKeys.contains(key))
                .map(key -> new PlaceholderItem(key, null))
                .toList();
    }

    /** 校验结果（三级清单；空构造便于断言与序列化）。 */
    public record ValidationResult(
            List<String> hardErrors, List<PlaceholderItem> removed, List<PlaceholderItem> unknown) {

        public ValidationResult {
            hardErrors = hardErrors == null ? List.of() : List.copyOf(hardErrors);
            removed = removed == null ? List.of() : List.copyOf(removed);
            unknown = unknown == null ? List.of() : List.copyOf(unknown);
        }

        /** 硬校验是否通过（V1/V2/V3 无违例）。 */
        public boolean hardPassed() {
            return hardErrors.isEmpty();
        }

        /** 放行条件：硬校验通过 且 removed ⊆ confirmedRemovedKeys（unknown 不参与；超集确认无害）。 */
        public boolean passableWith(Set<String> confirmedRemovedKeys) {
            Set<String> confirmed = confirmedRemovedKeys == null ? Set.of() : confirmedRemovedKeys;
            return hardPassed()
                    && removed.stream().map(PlaceholderItem::key).allMatch(confirmed::contains);
        }

        /** V5 警告文案（201 响应与保存确认 Dialog 回显用）。 */
        public List<String> unknownWarnings() {
            return unknown.stream()
                    .map(item -> "占位符 " + item.key() + " 无上下文来源，渲染时将原样发给模型")
                    .toList();
        }
    }

    /** removed/unknown 清单项（30068 data 与 warnings 的承载结构）。 */
    public record PlaceholderItem(String key, String description) {}
}
