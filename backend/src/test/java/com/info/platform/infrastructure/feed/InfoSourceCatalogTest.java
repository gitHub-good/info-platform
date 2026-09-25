package com.info.platform.infrastructure.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.info.platform.application.feed.SourceConfigValidator;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * InfoSourceCatalog 单测（T100，方案 §3.4/§4.6）：M13 试点三源齐备（覆盖 rss/json_api/preset 三通道）、source_code 唯一、 预置参数合法
 *（种子行能过实体构造与 config 校验——预置目录即白名单合规闸）。
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

        assertThat(codes).containsExactly(
                "mw_topstories", "jin10_flash", "sina_zhibo_7x24");
        assertThat(
                        InfoSourceCatalog.presets().stream()
                                .map(InfoSourceCatalog.PresetEntry::adapterType))
                .containsExactlyInAnyOrder(
                        AdapterType.RSS, AdapterType.JSON_API, AdapterType.PRESET);
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
