package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import com.info.platform.domain.aggregation.SourceCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 数据源域种子契约测试（T36；ADR-0032 起种子值全部取 {@link DataSourceDefaults} 代码内置缺省——原 yml {@code adapter:}
 * 段删除后的防漂移锁定）：7 键齐备、权威值形状锁定（供页面/消费点对齐）， mock 全局开关语义平移为分源 mode 种子默认值（MOCK）。
 */
class DataSourceRuntimeConfigSeederTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DataSourceRuntimeConfigSeeder seeder =
            new DataSourceRuntimeConfigSeeder(objectMapper);

    private List<RuntimeConfigSeed> seeds() {
        return seeder.seeds();
    }

    @Test
    void seeds_containAllSevenSourceKeys() {
        List<String> keys = seeds().stream().map(RuntimeConfigSeed::configKey).toList();
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "datasource.QUOTE",
                        "datasource.FINANCE",
                        "datasource.VALUATION",
                        "datasource.ANNOUNCE",
                        "datasource.NEWS",
                        "datasource.POLICY",
                        "datasource.EVENT");
    }

    @Test
    void quoteSeed_locksAuthoritativeValuesFromCodeDefaults() throws Exception {
        JsonNode doc = docOf("datasource.QUOTE");

        assertThat(doc.path("enabled").asBoolean()).isTrue();
        assertThat(doc.path("mode").asText())
                .isEqualTo("MOCK"); // 原 adapter.mock.enabled=true 的语义平移（ADR-0032）
        assertThat(doc.path("timeoutMillis").asLong()).isEqualTo(1500); // QuoteSourceAdapter 原 1.5s
        assertThat(doc.path("retries").asInt()).isZero(); // 原 noRetry
        assertThat(doc.path("cacheTtlSeconds").asLong()).isEqualTo(5); // SourceCache.specFor 原 5s
        assertThat(doc.path("failureCacheTtlSeconds").asLong()).isEqualTo(10); // P1-5b 行情负缓存 10s
        assertThat(doc.path("params").path("quoteUrl").asText())
                .isEqualTo("https://push2.eastmoney.com/api/qt/stock/get");
        assertThat(doc.path("params").path("fields").asText())
                .isEqualTo("f43,f44,f45,f46,f47,f48,f57,f58,f60,f168,f169,f170,f171");
        // ADR-0032 备选源开关热化：原 yml adapter.quote-source=auto 迁入 params
        assertThat(doc.path("params").path("backupSource").asText()).isEqualTo("auto");
    }

    @Test
    void financeSeed_ttlOneHour_policySeedTenMinutes() throws Exception {
        assertThat(docOf("datasource.FINANCE").path("cacheTtlSeconds").asLong()).isEqualTo(3600);
        assertThat(docOf("datasource.FINANCE").path("timeoutMillis").asLong()).isEqualTo(2000);
        assertThat(docOf("datasource.POLICY").path("cacheTtlSeconds").asLong()).isEqualTo(600);
    }

    @Test
    void eventSeed_localReadOnly_emptyParams() throws Exception {
        JsonNode doc = docOf("datasource.EVENT");

        assertThat(doc.path("timeoutMillis").asLong()).isEqualTo(500); // EventSourceAdapter 原 500ms
        assertThat(doc.path("cacheTtlSeconds").asLong()).isEqualTo(30);
        assertThat(doc.path("failureCacheTtlSeconds").asLong()).isEqualTo(30); // P1-5b 其余源 30s
        assertThat(doc.path("params")).isEmpty();
    }

    @Test
    void announceSeed_carriesCountTemplateAndRefererParams() throws Exception {
        JsonNode params = docOf("datasource.ANNOUNCE").path("params");

        assertThat(params.path("announceUrl").asText())
                .isEqualTo("https://np-anotice-stock.eastmoney.com/api/security/ann");
        assertThat(params.path("announcePageSize").asInt()).isEqualTo(3);
        assertThat(params.path("announceDetailUrlTemplate").asText())
                .isEqualTo("https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf");
        // ADR-0032 补齐：原 yml adapter.eastmoney.announce-referer 迁入 params（T36 时 client 热读但种子缺键）
        assertThat(params.path("announceReferer").asText())
                .isEqualTo("https://data.eastmoney.com/");
    }

    @Test
    void valuationSeed_carriesBackupSourceSwitch() throws Exception {
        JsonNode params = docOf("datasource.VALUATION").path("params");

        assertThat(params.path("quoteUrl").asText())
                .isEqualTo("https://push2.eastmoney.com/api/qt/stock/get");
        assertThat(params.path("valuationFields").asText()).isEqualTo("f57,f162,f167");
        assertThat(params.path("backupSource").asText()).isEqualTo("auto");
    }

    @Test
    void seeds_paramsMatchDataSourceDefaults_noDrift() throws Exception {
        // 种子 params 与 DataSourceDefaults 单一事实源逐键一致（防两处定义漂移，对齐 ADR-0032）
        for (SourceCode code : SourceCode.values()) {
            JsonNode seeded =
                    docOf(ConfigCenter.KEY_DATASOURCE_PREFIX + code.name()).path("params");
            DataSourceDefaults.params(code)
                    .forEach(
                            (key, value) ->
                                    assertThat(seeded.path(key).asText())
                                            .as("datasource.%s.params.%s", code, key)
                                            .isEqualTo(String.valueOf(value)));
        }
    }

    private JsonNode docOf(String key) throws Exception {
        RuntimeConfigSeed seed =
                seeds().stream()
                        .filter(s -> s.configKey().equals(key))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("缺种子: " + key));
        return objectMapper.readTree(seed.json());
    }
}
