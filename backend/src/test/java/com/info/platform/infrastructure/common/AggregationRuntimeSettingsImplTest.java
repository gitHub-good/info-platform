package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link AggregationRuntimeSettingsImpl} 单测（T36）：键存在读快照当前值（写后即热）、键缺失/非法回落 yml 值。 */
class AggregationRuntimeSettingsImplTest {

    private static final Instant T1 = Instant.parse("2026-09-22T00:00:00Z");

    private static class InMemoryRepository implements RuntimeConfigRepository {
        final Map<String, RuntimeConfig> rows = new LinkedHashMap<>();

        @Override
        public Optional<RuntimeConfig> findByKey(String configKey) {
            return Optional.ofNullable(rows.get(configKey));
        }

        @Override
        public List<RuntimeConfig> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public RuntimeConfig save(RuntimeConfig config) {
            rows.put(config.getConfigKey(), config);
            return config;
        }
    }

    private final InMemoryRepository repository = new InMemoryRepository();
    private RuntimeConfigService service;
    private ConfigCenter configCenter;
    private AggregationRuntimeSettingsImpl settings;

    @BeforeEach
    void setUp() {
        service =
                new RuntimeConfigService(
                        repository,
                        List.of(),
                        event -> {},
                        Clock.fixed(T1, ZoneOffset.UTC),
                        new ObjectMapper());
        com.info.platform.infrastructure.ai.LlmConfig llmConfig =
                new com.info.platform.infrastructure.ai.LlmConfig();
        llmConfig.setProviders(List.of());
        configCenter = new ConfigCenter(service, List.of(), llmConfig, null, new ObjectMapper());
        settings = new AggregationRuntimeSettingsImpl(configCenter, 2500L);
    }

    @Test
    void keyAbsent_fallsBackToYmlValue() {
        assertThat(settings.detailTimeoutMillis()).isEqualTo(2500L);
    }

    @Test
    void keyPresent_readsSnapshotValue() {
        repository.save(
                RuntimeConfig.create(
                        "aggregation.global", "{\"detailTimeoutMillis\":3000}", null, T1));
        service.reload();

        assertThat(settings.detailTimeoutMillis()).isEqualTo(3000L);
    }

    @Test
    void writeChangesValue_visibleOnNextRead() {
        repository.save(
                RuntimeConfig.create(
                        "aggregation.global", "{\"detailTimeoutMillis\":2000}", null, T1));
        service.reload();
        repository.save(
                RuntimeConfig.create(
                        "aggregation.global", "{\"detailTimeoutMillis\":4000}", null, T1));
        service.reload();

        assertThat(settings.detailTimeoutMillis()).isEqualTo(4000L);
    }

    @Test
    void nonPositiveValue_fallsBackToYmlValue() {
        repository.save(
                RuntimeConfig.create(
                        "aggregation.global", "{\"detailTimeoutMillis\":0}", null, T1));
        service.reload();

        assertThat(settings.detailTimeoutMillis()).isEqualTo(2500L);
    }

    @Test
    void dataSourceViewAccessors() {
        // 附带覆盖 RuntimeDataSource 访问器分支（paramInt 数字/字符串/非法/缺失 + duration 换算 + null params 收敛）
        RuntimeDataSource view =
                new RuntimeDataSource(
                        SourceCode.ANNOUNCE, true, RuntimeDataSource.Mode.REAL, 2000, 1, 300, null);
        assertThat(view.params()).isEmpty();
        assertThat(view.timeout().toMillis()).isEqualTo(2000);
        assertThat(view.cacheTtl().toSeconds()).isEqualTo(300);
        assertThat(view.paramInt("announcePageSize", 3)).isEqualTo(3);

        RuntimeDataSource rich =
                new RuntimeDataSource(
                        SourceCode.NEWS,
                        true,
                        RuntimeDataSource.Mode.MOCK,
                        1000,
                        0,
                        60,
                        Map.of("newsPageSize", 20, "newsLid", "2510", "bad", "x"));
        assertThat(rich.paramInt("newsPageSize", 5)).isEqualTo(20);
        assertThat(rich.paramInt("newsLid", 5)).isEqualTo(2510);
        assertThat(rich.paramInt("bad", 5)).isEqualTo(5);
        assertThat(rich.paramInt("absent", 7)).isEqualTo(7);
        assertThat(rich.paramString("newsLid", "x")).isEqualTo("2510");
        assertThat(rich.paramString("absent", "x")).isEqualTo("x");
    }
}
