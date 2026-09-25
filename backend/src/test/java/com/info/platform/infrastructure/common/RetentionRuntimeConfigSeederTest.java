package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import org.junit.jupiter.api.Test;

/**
 * RetentionRuntimeConfigSeeder 单测（T72，M10 技术方案增补 §4.1）：retention.global 种子键形态与默认窗口 （枚举 defaultDays
 * 单一事实源 30/14/90/90）；seed-if-absent 幂等由 RuntimeConfigService.seedIfAbsent 通用机制保证 （DB
 * 已有键不动，已有测试覆盖），此处锁定种子结构与值。
 */
class RetentionRuntimeConfigSeederTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeConfigSeed seed() {
        return new RetentionRuntimeConfigSeeder(objectMapper).seeds().get(0);
    }

    @Test
    void seeds_retentionGlobal_defaultsFromEnum() throws Exception {
        RuntimeConfigSeed seed = seed();

        assertThat(seed.configKey()).isEqualTo("retention.global");
        assertThat(seed.description()).contains("留痕");
        JsonNode doc = objectMapper.readTree(seed.json());
        // 四字段默认窗口 = 枚举 defaultDays（30/14/90/90，ADR-0032 代码内置默认同系列）
        assertThat(doc.get("jobExecutionLogDays").asInt()).isEqualTo(30);
        assertThat(doc.get("dataSourceEventDays").asInt()).isEqualTo(14);
        assertThat(doc.get("llmCallLogDays").asInt()).isEqualTo(90);
        assertThat(doc.get("readingEventDays").asInt()).isEqualTo(90);
    }

    @Test
    void seeds_singleKey_jsonReserializable() {
        // 单键种子（与每表四键的形态对照：四字段恒同读同写，单文档无部分写中间态）
        assertThat(new RetentionRuntimeConfigSeeder(objectMapper).seeds()).hasSize(1);
        assertThat(seed().json()).contains("jobExecutionLogDays").contains("readingEventDays");
    }
}
