package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.feed.SourceConfigValidator;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * InfoSourceCatalog 单测（T100，方案 §3.4/§4.6）：M13 试点三源齐备（覆盖 rss/json_api/preset 三通道）、source_code 唯一、
 * 预置参数合法 （种子行能过实体构造与 config 校验——预置目录即白名单合规闸）。
 */
class InfoSourceCatalogTest {

    private final SourceConfigCodec codec = new SourceConfigCodec();
    private final SourceConfigValidator validator = new SourceConfigValidator();

    @Test
    void presets_containsThreePilotChannels_coveringAllAdapterTypes() {
        var codes =
                InfoSourceCatalog.presets().stream()
                        .map(InfoSourceCatalog.PresetEntry::sourceCode)
                        .toList();

        // M13 试点三源 + M14 T110 批次一一级 JSON 四源 + T111/T112 官方与报纸 HTML 六源（种子顺序即展示顺序）
        assertThat(codes)
                .containsExactly(
                        "mw_topstories",
                        "jin10_flash",
                        "sina_zhibo_7x24",
                        "em_fastnews_7x24",
                        "ths_push_stock",
                        "thepaper_hotnews",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance");
        assertThat(
                        InfoSourceCatalog.presets().stream()
                                .map(InfoSourceCatalog.PresetEntry::adapterType))
                .contains(AdapterType.RSS, AdapterType.JSON_API, AdapterType.PRESET);
    }

    @Test
    void presets_containsM14BatchOneJsonSources_thirteenPresetsTotal() {
        // M14 T110 一级 JSON 四源 + T111/T112 官方报纸六源入目录（REQ 累计 13 源口径）
        var codes =
                InfoSourceCatalog.presets().stream()
                        .map(InfoSourceCatalog.PresetEntry::sourceCode)
                        .toList();

        assertThat(codes)
                .contains(
                        "em_fastnews_7x24",
                        "ths_push_stock",
                        "thepaper_hotnews",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance");
        assertThat(codes).hasSize(13);
    }

    @Test
    void presets_containsM14BatchOneHtmlSources_presetChannelCursorNone() {
        // T111/T112 六源：全部 preset 通道（HTML 列表适配在代码内）+ cursorType=NONE（增量靠唯一索引，
        // 日期粒度过粗/列表乱序/相对时间的裁量见 ADR-0044）+ 频控落 REQ 频段（官方 60min、报纸 15~30min）
        var htmlCodes =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.adapterType() == AdapterType.PRESET)
                        .toList();
        assertThat(htmlCodes)
                .extracting(InfoSourceCatalog.PresetEntry::sourceCode)
                .containsExactly(
                        "sina_zhibo_7x24",
                        "em_macro_indicators",
                        "ndrc_policy",
                        "csrc_news",
                        "stats_release",
                        "stcn_news",
                        "yicai_news",
                        "jingji21_finance");
        for (String code :
                new String[] {
                    "ndrc_policy",
                    "csrc_news",
                    "stats_release",
                    "stcn_news",
                    "yicai_news",
                    "jingji21_finance"
                }) {
            var entry =
                    htmlCodes.stream()
                            .filter(p -> p.sourceCode().equals(code))
                            .findFirst()
                            .orElseThrow();
            var config = codec.parse(entry.configJson());
            assertThat(config.effectiveCursorType().name())
                    .as("%s cursorType", code)
                    .isEqualTo("NONE");
            assertThat(entry.adapterRef()).as("%s adapter_ref", code).isNotBlank();
            int interval = entry.intervalMinutes();
            if (code.endsWith("_policy")
                    || code.equals("csrc_news")
                    || code.equals("stats_release")) {
                assertThat(interval).as("%s 官方源频控 30~60", code).isBetween(30, 60);
            } else {
                assertThat(interval).as("%s 报纸源频控 15~30", code).isBetween(15, 30);
            }
        }
    }

    @Test
    void presets_sourceCodesUnique() {
        Set<String> seen = new HashSet<>();
        for (InfoSourceCatalog.PresetEntry entry : InfoSourceCatalog.presets()) {
            assertThat(seen.add(entry.sourceCode()))
                    .as("source_code 重复: %s", entry.sourceCode())
                    .isTrue();
        }
    }

    @Test
    void presets_entriesConstructValidEntities_andPassConfigValidation() {
        for (InfoSourceCatalog.PresetEntry entry : InfoSourceCatalog.presets()) {
            InfoSource source =
                    InfoSource.create(
                            entry.sourceCode(),
                            entry.name(),
                            entry.category(),
                            entry.adapterType(),
                            entry.adapterRef(),
                            entry.endpoint(),
                            codec.parse(entry.configJson()),
                            entry.intervalMinutes(),
                            true,
                            true);
            // 预置源走 common 校验（preset 类型仅 create 受限）；配置漂移在此红灯
            validator.validateCommon(source);
            assertThat(source.isPreset()).isTrue();
            assertThat(source.isEnabled()).isTrue();
        }
    }

    @Test
    void presets_jin10ConfigCarriesStripPrefixAndIdCursor() {
        var jin10 =
                InfoSourceCatalog.presets().stream()
                        .filter(p -> p.sourceCode().equals("jin10_flash"))
                        .findFirst()
                        .orElseThrow();
        var config = codec.parse(jin10.configJson());

        assertThat(config.stripPrefix()).isEqualTo("var newest=");
        assertThat(config.stripSuffix()).isEqualTo(";");
        assertThat(config.effectiveCursorType().name()).isEqualTo("ID");
        assertThat(config.mappings()).isNotEmpty();
        assertThat(config.headers()).containsKey("User-Agent");
    }
}
