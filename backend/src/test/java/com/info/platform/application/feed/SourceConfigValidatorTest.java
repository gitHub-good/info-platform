package com.info.platform.application.feed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.info.platform.domain.common.BusinessException;
import com.info.platform.domain.common.ErrorCode;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.CursorType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.SourceConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SourceConfigValidator 单测（T100，方案 §4.5 错误码 30072 字段级提示）：类型白名单/URL/映射结构/headers 白名单/游标声明/数值边界。 */
class SourceConfigValidatorTest {

    private final SourceConfigValidator validator = new SourceConfigValidator();

    private static InfoSource rssSource(SourceConfig config, int intervalMinutes) {
        return InfoSource.create(
                "t100_v",
                "校验源",
                "媒体",
                AdapterType.RSS,
                null,
                "https://example.com/rss",
                config,
                intervalMinutes,
                true,
                false);
    }

    private static SourceConfig.ItemMapping mapping(
            String source, String target, String transform) {
        return new SourceConfig.ItemMapping(source, target, transform);
    }

    @Test
    void validateCreate_minimalRssSource_passes() {
        InfoSource source = rssSource(SourceConfig.empty(), 15);

        assertThatCode(() -> validator.validateCreate(source)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCommon(source)).doesNotThrowAnyException();
    }

    @Test
    void validateCreate_rejectsPresetAndHtmlTemplateAdapterTypes() {
        InfoSource preset =
                InfoSource.create(
                        "t100_vp",
                        "预置",
                        "快讯",
                        AdapterType.PRESET,
                        "someBean",
                        "https://example.com/api",
                        SourceConfig.empty(),
                        5,
                        true,
                        false);
        InfoSource html =
                InfoSource.create(
                        "t100_vh",
                        "模板",
                        "媒体",
                        AdapterType.HTML_TEMPLATE,
                        null,
                        "https://example.com/list",
                        SourceConfig.empty(),
                        15,
                        true,
                        false);

        assertThatThrownBy(() -> validator.validateCreate(preset))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("adapterType")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_CONFIG_INVALID);
        assertThatThrownBy(() -> validator.validateCreate(html))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("adapterType");
    }

    @Test
    void validateCommon_rejectsNonHttpEndpoint() {
        InfoSource source =
                InfoSource.create(
                        "t100_vu",
                        "源",
                        "媒体",
                        AdapterType.RSS,
                        null,
                        "ftp://example.com/rss",
                        SourceConfig.empty(),
                        15,
                        true,
                        false);

        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("endpoint")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INFO_SOURCE_CONFIG_INVALID);
    }

    @Test
    void validateCommon_intervalGuardedByEntity() {
        // 频控 0 分钟：实体构造期即拒绝（IllegalArgumentException，领域规则单一事实源）
        assertThatThrownBy(
                        () ->
                                validator.validateCommon(
                                        InfoSource.create(
                                                "t100_vi",
                                                "源",
                                                "媒体",
                                                AdapterType.RSS,
                                                null,
                                                "https://example.com/rss",
                                                SourceConfig.empty(),
                                                0,
                                                true,
                                                false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateCommon_jsonApiRequiresTitleMapping() {
        // json_api 无映射 → 拒绝（映射必填字段，蓝图故事 1 场景 2 可诊断）
        SourceConfig noMapping =
                new SourceConfig("", null, null, List.of(), null, null, null, null, null);
        InfoSource source =
                InfoSource.create(
                        "t100_vj",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        noMapping,
                        5,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemMapping");

        // 有 title 映射 → 通过
        SourceConfig withTitle =
                new SourceConfig(
                        "",
                        null,
                        null,
                        List.of(mapping("title", "title", "to_string")),
                        null,
                        null,
                        null,
                        null,
                        null);
        InfoSource ok =
                InfoSource.create(
                        "t100_vj2",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        withTitle,
                        5,
                        true,
                        false);
        assertThatCode(() -> validator.validateCommon(ok)).doesNotThrowAnyException();
    }

    @Test
    void validateCommon_unknownTransformRejected() {
        SourceConfig bad =
                new SourceConfig(
                        "",
                        null,
                        null,
                        List.of(mapping("title", "title", "explode")),
                        null,
                        null,
                        null,
                        null,
                        null);
        InfoSource source =
                InfoSource.create(
                        "t100_vt",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        bad,
                        5,
                        true,
                        false);

        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("transform");
    }

    @Test
    void validateCommon_mappingFieldBlankRejected() {
        SourceConfig bad =
                new SourceConfig(
                        "",
                        null,
                        null,
                        List.of(mapping(" ", "title", "to_string")),
                        null,
                        null,
                        null,
                        null,
                        null);
        InfoSource source =
                InfoSource.create(
                        "t100_vb",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        bad,
                        5,
                        true,
                        false);

        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemMapping");
    }

    @Test
    void validateCommon_headersWhitelistOnly() {
        // Cookie 等头拒绝（config 禁止存放密钥，方案 §5）
        SourceConfig withSecret =
                new SourceConfig(
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of("Cookie", "session=abc"),
                        null,
                        null,
                        null,
                        null);
        InfoSource source =
                InfoSource.create(
                        "t100_vh2",
                        "源",
                        "媒体",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss",
                        withSecret,
                        15,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("headers");

        // UA/Referer 白名单内 → 通过
        SourceConfig okHeaders =
                new SourceConfig(
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of("User-Agent", "Mozilla/5.0", "Referer", "https://example.com"),
                        null,
                        null,
                        null,
                        null);
        InfoSource ok =
                InfoSource.create(
                        "t100_vh3",
                        "源",
                        "媒体",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss",
                        okHeaders,
                        15,
                        true,
                        false);
        assertThatCode(() -> validator.validateCommon(ok)).doesNotThrowAnyException();
    }

    @Test
    void validateCommon_cursorDeclarationConsistency() {
        // cursorType=ID 但未声明 cursorField → 拒绝
        SourceConfig noField =
                new SourceConfig(
                        null, null, null, List.of(), null, null, null, CursorType.ID, null);
        InfoSource source =
                InfoSource.create(
                        "t100_vc",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        noField,
                        5,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cursorField");

        // cursorType=NONE 携带 cursorField → 拒绝（声明冗余即配置漂移隐患）
        SourceConfig redundant =
                new SourceConfig(
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        CursorType.NONE,
                        "externalId");
        InfoSource source2 =
                InfoSource.create(
                        "t100_vc2",
                        "源",
                        "快讯",
                        AdapterType.JSON_API,
                        null,
                        "https://example.com/api",
                        redundant,
                        5,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    void validateCommon_numericBounds() {
        // maxItems 1~200 / pageSize 1~100
        SourceConfig over =
                new SourceConfig(null, null, null, List.of(), null, 500, null, null, null);
        InfoSource source =
                InfoSource.create(
                        "t100_vn",
                        "源",
                        "媒体",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss",
                        over,
                        15,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maxItems");

        SourceConfig pageSizeBad =
                new SourceConfig(null, null, null, List.of(), null, null, 0, null, null);
        InfoSource source2 =
                InfoSource.create(
                        "t100_vn2",
                        "源",
                        "媒体",
                        AdapterType.RSS,
                        null,
                        "https://example.com/rss",
                        pageSizeBad,
                        15,
                        true,
                        false);
        assertThatThrownBy(() -> validator.validateCommon(source2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pageSize");
    }
}
