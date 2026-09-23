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

/**
 * {@link ConfigCenter#dataSource(SourceCode)} 视图测试（T36）：键存在时类型化解析（含 params 访问器）、
 * 写后快照替换即热生效、键缺失回落代码缺省（mode 按全局 mock 开关裁定）、损坏文档回落不阻断。
 */
class ConfigCenterDataSourceViewTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryRepository repository = new InMemoryRepository();
    private RuntimeConfigService service;
    private ConfigCenter configCenter;

    @BeforeEach
    void setUp() {
        service =
                new RuntimeConfigService(
                        repository,
                        List.of(),
                        event -> {},
                        Clock.fixed(T1, ZoneOffset.UTC),
                        objectMapper);
        configCenter = new ConfigCenter(service, List.of(), null, objectMapper);
    }

    private void store(String key, String json) {
        repository.save(RuntimeConfig.create(key, json, null, T1));
        service.reload();
    }

    @Test
    void dataSource_parsesTypedViewWithParamAccessors() {
        store(
                "datasource.QUOTE",
                """
                {"enabled":true,"mode":"REAL","timeoutMillis":1200,"retries":2,
                 "cacheTtlSeconds":15,
                 "params":{"quoteUrl":"https://q.example.com/get","fields":"f43,f57"}}
                """);

        RuntimeDataSource view = configCenter.dataSource(SourceCode.QUOTE);

        assertThat(view.enabled()).isTrue();
        assertThat(view.mode()).isEqualTo(RuntimeDataSource.Mode.REAL);
        assertThat(view.timeout().toMillis()).isEqualTo(1200);
        assertThat(view.retries()).isEqualTo(2);
        assertThat(view.cacheTtl().toSeconds()).isEqualTo(15);
        assertThat(view.paramString("quoteUrl", "fallback")).isEqualTo("https://q.example.com/get");
        assertThat(view.paramString("absent", "fallback")).isEqualTo("fallback");
    }

    @Test
    void dataSource_afterWrite_snapshotReplacedHot() {
        store("datasource.NEWS", newsDoc(RuntimeDataSource.Mode.REAL));
        assertThat(configCenter.dataSource(SourceCode.NEWS).mode())
                .isEqualTo(RuntimeDataSource.Mode.REAL);

        repository.save(
                RuntimeConfig.create(
                        "datasource.NEWS", newsDoc(RuntimeDataSource.Mode.MOCK), null, T1));
        service.reload();

        assertThat(configCenter.dataSource(SourceCode.NEWS).mode())
                .isEqualTo(RuntimeDataSource.Mode.MOCK);
    }

    @Test
    void dataSource_keyAbsent_fallsBackToCodeDefaults_mockMode() {
        // 纯构造（@Value 未处理）：字段默认 true → MOCK，对齐改造前 matchIfMissing=true
        RuntimeDataSource view = configCenter.dataSource(SourceCode.VALUATION);

        assertThat(view.enabled()).isTrue();
        assertThat(view.mode()).isEqualTo(RuntimeDataSource.Mode.MOCK);
        assertThat(view.timeoutMillis()).isEqualTo(2000);
        assertThat(view.cacheTtlSeconds()).isEqualTo(3600);
        assertThat(view.paramString("valuationFields", "x")).isEqualTo("f57,f162,f167");
    }

    @Test
    void dataSource_corruptDocument_fallsBackWithoutBreaking() {
        store("datasource.POLICY", "{\"enabled\":true,\"mode\":\"NOT_A_MODE\"}");

        RuntimeDataSource view = configCenter.dataSource(SourceCode.POLICY);

        assertThat(view.mode()).isEqualTo(RuntimeDataSource.Mode.MOCK);
        assertThat(view.timeoutMillis()).isEqualTo(2000);
    }

    private static String newsDoc(RuntimeDataSource.Mode mode) {
        return "{\"enabled\":true,\"mode\":\""
                + mode.name()
                + "\",\"timeoutMillis\":2000,\"retries\":0,\"cacheTtlSeconds\":120,\"params\":{\"newsPageSize\":20}}";
    }
}
