package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigEntry;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.analysis.NearDuplicateDetector.DupParams;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * PipelineSettings 单测（T121，方案 §4.8 配置键消费点）：热改键值驱动、键缺失/JSON 损坏回落代码缺省、 l1BatchSize 越界回落（&gt;30
 * 禁配红线）、noise 关键词/正则注入与非法正则剔除。AAA 结构。
 */
class PipelineSettingsTest {

    private final RuntimeConfigService configService = mock(RuntimeConfigService.class);

    private final PipelineSettings settings =
            new PipelineSettings(configService, new ObjectMapper());

    private void stubKey(String key, String json) {
        when(configService.read(key))
                .thenReturn(Optional.of(new RuntimeConfigEntry(key, json, null, null, null)));
    }

    @Test
    void defaults_whenKeysMissing() {
        when(configService.read(anyString())).thenReturn(Optional.empty());

        assertThat(settings.l1BatchSize()).isEqualTo(20);
        assertThat(settings.confidenceFloor()).isEqualTo(0.45);
        assertThat(settings.maxRetriesPerDay()).isEqualTo(3);
        assertThat(settings.l1BackfillHours()).isEqualTo(24);
        assertThat(settings.l0BufferMinutes()).isEqualTo(2);
        assertThat(settings.dupParams()).isEqualTo(new DupParams(18, 0.25, 8)); // ADR-0047 勘定 18
        assertThat(settings.nearDupWindowHours()).isEqualTo(24);
    }

    @Test
    void hotConfig_overridesDefaults() {
        stubKey(
                "pipeline.global",
                """
                {"batchWindowMinutes":10,"l1BatchSize":30,"confidenceFloor":0.5,
                 "maxRetriesPerDay":5,"l1BackfillHours":12,"l0BufferMinutes":5}
                """);
        stubKey(
                "pipeline.l0",
                """
                {"noiseKeywords":["自定义词"],"noisePatterns":["(专测).*命中"],
                 "simhashDistanceMax":12,"editDistanceMax":0.2,
                 "nearDupWindowHours":48,"minTitleLength":10}
                """);

        assertThat(settings.l1BatchSize()).isEqualTo(30);
        assertThat(settings.confidenceFloor()).isEqualTo(0.5);
        assertThat(settings.maxRetriesPerDay()).isEqualTo(5);
        assertThat(settings.l1BackfillHours()).isEqualTo(12);
        assertThat(settings.l0BufferMinutes()).isEqualTo(5);
        assertThat(settings.dupParams()).isEqualTo(new DupParams(12, 0.2, 10));
        assertThat(settings.nearDupWindowHours()).isEqualTo(48);
        // 自定义 noise 规则生效：命中自定义词，缺省词不再命中
        assertThat(settings.noiseRuleEngine().evaluate("这是专测规则命中", null)).isPresent();
        assertThat(settings.noiseRuleEngine().evaluate("广告合作", null)).isEmpty();
    }

    @Test
    void l1BatchSize_outOfRange_fallsBackToDefault() {
        // ADR-0046 裁决 2：>30 禁配（截断与失败爆炸半径红线）；<10 同样回落
        stubKey("pipeline.global", "{\"l1BatchSize\":50}");

        assertThat(settings.l1BatchSize()).isEqualTo(20);

        stubKey("pipeline.global", "{\"l1BatchSize\":5}");
        assertThat(settings.l1BatchSize()).isEqualTo(20);
    }

    @Test
    void corruptedJson_fallsBackToDefaults() {
        stubKey("pipeline.global", "not-a-json{{{");
        stubKey("pipeline.l0", "broken");

        assertThat(settings.l1BatchSize()).isEqualTo(20);
        assertThat(settings.dupParams()).isEqualTo(new DupParams(18, 0.25, 8));
    }

    @Test
    void invalidRegexInPatterns_droppedOthersKept() {
        stubKey(
                "pipeline.l0",
                "{\"noiseKeywords\":[\"自定义词\"],\"noisePatterns\":[\"([坏了的正则\",\"(可用).*规则\"]}");

        assertThat(settings.noiseRuleEngine().evaluate("这是可用规则", null)).isPresent(); // 合法正则生效
        assertThat(settings.noiseRuleEngine().evaluate("广告合作", null)).isEmpty(); // 缺省词表已被自定义覆盖
    }

    @Test
    void emptyCustomRules_fallBackToDefaults() {
        // 关键词与正则全空 → 回落缺省引擎（防止热改成空规则导致 noise 检测整体失效）
        stubKey("pipeline.l0", "{\"noiseKeywords\":[],\"noisePatterns\":[]}");

        assertThat(settings.noiseRuleEngine().evaluate("广告合作", null)).isPresent();
    }

    @Test
    void constants_matchDesignDefaults() {
        assertThat(PipelineSettings.L1_TEMPERATURE).isEqualTo(0.1);
        assertThat(PipelineSettings.L1_MAX_TOKENS).isEqualTo(8192);
        assertThat(PipelineSettings.L1_TICK_CAP).isEqualTo(400);
        assertThat(PipelineSettings.L0_INTAKE_CAP_PER_TICK).isEqualTo(200);
    }
}
