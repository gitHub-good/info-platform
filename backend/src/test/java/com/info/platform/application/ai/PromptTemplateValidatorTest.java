package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.domain.ai.PlaceholderDescriptor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PromptTemplateValidator 单测（T45，方案 §4.3 规则表 V1~V5 + 放行条件）：硬校验三态/大小写/边界、移除 diff（基准底稿）、 unknown
 * 差集（警告级不拦）、确认数组部分/全量/超集语义。
 */
class PromptTemplateValidatorTest {

    private static final List<PlaceholderDescriptor> REGISTRY =
            List.of(
                    new PlaceholderDescriptor("subjectName", "标的名称"),
                    new PlaceholderDescriptor("subjectCode", "标的代码"),
                    new PlaceholderDescriptor("price", "当前价"));

    private static final String VALID_TEMPLATE =
            "---SYSTEM---\n你是分析师。生成结构化 json 简报。\n---USER---\n标的：{{subjectName}}({{subjectCode}}) {{price}}";

    private static final String BASE_TEMPLATE =
            "---SYSTEM---\n你是分析师。生成结构化 json 简报。\n---USER---\n标的：{{subjectName}}({{subjectCode}}) {{price}}";

    @Test
    void validate_validTemplate_noErrorsAndNoRemovals() {
        // Act
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(VALID_TEMPLATE, BASE_TEMPLATE, REGISTRY);

        // Assert：V1/V2/V3 全过；未移除任何键
        assertThat(r.hardErrors()).isEmpty();
        assertThat(r.removed()).isEmpty();
        assertThat(r.unknown()).isEmpty();
        assertThat(r.passableWith(Set.of())).isTrue();
    }

    @Test
    void validate_v1_missingMarkers_reportsEachSeparately() {
        // Act：缺 USER 标记
        PromptTemplateValidator.ValidationResult noUser =
                PromptTemplateValidator.validate("---SYSTEM---\n含 json 的系统段", null, REGISTRY);
        // Act：两个标记都缺
        PromptTemplateValidator.ValidationResult noMarkers =
                PromptTemplateValidator.validate("只有正文", null, REGISTRY);

        // Assert：逐条结构化文案（故事 2 场景 3/4 契约：msg 含具体缺失项）
        assertThat(noUser.hardErrors()).containsExactly("缺少 ---USER--- 分段标记");
        assertThat(noMarkers.hardErrors())
                .containsExactly("缺少 ---SYSTEM--- 分段标记", "缺少 ---USER--- 分段标记");
        assertThat(noUser.passableWith(Set.of())).isFalse();
    }

    @Test
    void validate_v1_reversedOrder_reportsOrderError() {
        // Arrange：USER 在 SYSTEM 前
        String reversed = "---USER---\nu json\n---SYSTEM---\ns";

        // Act + Assert
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(reversed, null, REGISTRY);
        assertThat(r.hardErrors()).singleElement().asString().contains("顺序错误");
    }

    @Test
    void validate_v2_systemWithoutJson_reportsHardError_caseInsensitive() {
        // Act：system 段无 "json" 字样
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(
                        "---SYSTEM---\n你是分析师。\n---USER---\n{{subjectName}}", null, REGISTRY);
        // Act：大写 JSON 应通过（大小写不敏感）
        PromptTemplateValidator.ValidationResult upper =
                PromptTemplateValidator.validate(
                        "---SYSTEM---\n输出 JSON 对象。\n---USER---\n{{subjectName}}", null, REGISTRY);

        // Assert
        assertThat(r.hardErrors()).containsExactly("system 段须含 json 字样（JSON 输出模式前提）");
        assertThat(upper.hardErrors()).isEmpty();
    }

    @Test
    void validate_v3_blankOrNull_reportsEmptyError() {
        assertThat(PromptTemplateValidator.validate(null, null, REGISTRY).hardErrors())
                .containsExactly("模板不能为空");
        assertThat(PromptTemplateValidator.validate("   ", null, REGISTRY).hardErrors())
                .containsExactly("模板不能为空");
    }

    @Test
    void validate_v3_overMaxLength_reportsLengthError() {
        // Arrange：65536 + 1 字符（含合法标记与 json，隔离长度规则）
        StringBuilder sb = new StringBuilder("---SYSTEM---\njson\n---USER---\n");
        sb.append("a".repeat(PromptTemplateValidator.MAX_TEMPLATE_LENGTH));
        String over = sb.toString();
        assertThat(over.length()).isGreaterThan(PromptTemplateValidator.MAX_TEMPLATE_LENGTH);

        // Act + Assert
        assertThat(PromptTemplateValidator.validate(over, null, REGISTRY).hardErrors())
                .containsExactly("模板长度超过上限 " + PromptTemplateValidator.MAX_TEMPLATE_LENGTH + " 字符");
    }

    @Test
    void validate_v4_removedCarriesRegistryDescription() {
        // Arrange：新模板移除 subjectCode（底稿有）
        String next = "---SYSTEM---\njson\n---USER---\n标的：{{subjectName}} {{price}} {{brandNew}}";

        // Act
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(next, BASE_TEMPLATE, REGISTRY);

        // Assert：removed = {subjectCode}（带注册表说明）；unknown = {brandNew}（description null）
        assertThat(r.removed())
                .containsExactly(
                        new PromptTemplateValidator.PlaceholderItem("subjectCode", "标的代码"));
        assertThat(r.unknown())
                .containsExactly(new PromptTemplateValidator.PlaceholderItem("brandNew", null));
        // 未确认 → 不放行；unknown 不参与放行判定
        assertThat(r.passableWith(Set.of())).isFalse();
    }

    @Test
    void validate_v4_removedKeyNotInRegistry_usesNoSourceDescription() {
        // Arrange：底稿含无来源键（如场景 2 休眠事件键），新模板移除之
        String base = "---SYSTEM---\njson\n---USER---\n{{eventTitle}} {{subjectName}}";
        String next = "---SYSTEM---\njson\n---USER---\n{{subjectName}}";

        // Act + Assert：移除无来源键同样进 removed（危险级确认通道），说明为占位文案
        assertThat(PromptTemplateValidator.validate(next, base, REGISTRY).removed())
                .containsExactly(
                        new PromptTemplateValidator.PlaceholderItem(
                                "eventTitle", PromptTemplateValidator.NO_SOURCE_DESCRIPTION));
    }

    @Test
    void validate_v4_nullOrEmptyBase_removedAlwaysEmpty() {
        // Act + Assert：无激活版/底稿缺失 → 基准空集（任何键都不算移除）
        assertThat(PromptTemplateValidator.validate(VALID_TEMPLATE, null, REGISTRY).removed())
                .isEmpty();
        assertThat(PromptTemplateValidator.validate(VALID_TEMPLATE, "", REGISTRY).removed())
                .isEmpty();
    }

    @Test
    void passableWith_fullConfirmationPasses_supersetHarmless_partialBlocked() {
        // Arrange：底稿三键，新模板仅留 subjectName → removed = {subjectCode, price}
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(
                        "---SYSTEM---\njson\n---USER---\n{{subjectName}}", BASE_TEMPLATE, REGISTRY);

        // Act + Assert：全确认放行
        assertThat(r.passableWith(Set.of("subjectCode", "price"))).isTrue();
        // 超集确认无害（多余键名忽略）
        assertThat(r.passableWith(Set.of("subjectCode", "price", "whatever"))).isTrue();
        // 部分确认仍拦（30068）
        assertThat(r.passableWith(Set.of("subjectCode"))).isFalse();
        assertThat(r.passableWith(null)).isFalse();
    }

    @Test
    void unknownAlone_doesNotBlock_andProducesWarnings() {
        // Arrange：仅含未知占位符（无移除）
        String next = "---SYSTEM---\njson\n---USER---\n{{foo}} {{bar}}";

        // Act
        PromptTemplateValidator.ValidationResult r =
                PromptTemplateValidator.validate(next, next, REGISTRY);

        // Assert：unknown 不拦截（PRD「仅警告/不阻断」）；warnings 文案逐键回显
        assertThat(r.passableWith(Set.of())).isTrue();
        assertThat(r.unknownWarnings())
                .containsExactly("占位符 foo 无上下文来源，渲染时将原样发给模型", "占位符 bar 无上下文来源，渲染时将原样发给模型");
    }
}
