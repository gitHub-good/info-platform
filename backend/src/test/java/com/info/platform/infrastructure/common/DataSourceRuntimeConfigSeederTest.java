package com.info.platform.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigSeed;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 数据源域种子契约测试（T36）：7 键齐备、权威值形状锁定（供页面/消费点对齐）， mock 全局开关语义平移为分源 mode 种子默认值。 */
class DataSourceRuntimeConfigSeederTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withConfiguration(UserConfigurations.of(SeederConfig.class))
                    .withPropertyValues(
                            "adapter.mock.enabled=false",
                            "adapter.eastmoney.quote-url=https://quote.example.com/get",
                            "adapter.eastmoney.fields=f43,f57",
                            "adapter.eastmoney.valuation-fields=f57,f162",
                            "adapter.eastmoney.finance-url=https://fin.example.com/api",
                            "adapter.eastmoney.finance-referer=https://fin.example.com/",
                            "adapter.eastmoney.announce-url=https://ann.example.com/api",
                            "adapter.eastmoney.announce-page-size=5",
                            "adapter.eastmoney.announce-detail-url-template=https://pdf.example.com/{art_code}.pdf",
                            "adapter.sina.news-url=https://news.example.com/roll",
                            "adapter.sina.news-page-id=1",
                            "adapter.sina.news-lid=2",
                            "adapter.sina.news-page-size=7",
                            "adapter.sina.news-referer=https://news.example.com",
                            "adapter.gov.policy-url=https://gov.example.com/zhengce/",
                            "adapter.gov.policy-referer=https://gov.example.com/");

    @TestConfiguration
    static class SeederConfig {
        @org.springframework.context.annotation.Bean
        DataSourceRuntimeConfigSeeder seeder(ObjectMapper objectMapper) {
            return new DataSourceRuntimeConfigSeeder(objectMapper);
        }
    }

    /** 在测试上下文内取种子（yml @Value 绑定需 Spring 处理；上下文随 run 关闭）。 */
    private List<RuntimeConfigSeed> seeds() {
        List<RuntimeConfigSeed>[] holder = new List[1];
        runner.run(
                context ->
                        holder[0] = context.getBean(DataSourceRuntimeConfigSeeder.class).seeds());
        return holder[0];
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
    void quoteSeed_locksAuthoritativeValuesExtractedFromCode() throws Exception {
        JsonNode doc = docOf("datasource.QUOTE");

        assertThat(doc.path("enabled").asBoolean()).isTrue();
        assertThat(doc.path("mode").asText()).isEqualTo("REAL"); // adapter.mock.enabled=false
        assertThat(doc.path("timeoutMillis").asLong()).isEqualTo(1500); // QuoteSourceAdapter 原 1.5s
        assertThat(doc.path("retries").asInt()).isZero(); // 原 noRetry
        assertThat(doc.path("cacheTtlSeconds").asLong()).isEqualTo(5); // SourceCache.specFor 原 5s
        assertThat(doc.path("params").path("quoteUrl").asText())
                .isEqualTo("https://quote.example.com/get");
        assertThat(doc.path("params").path("fields").asText()).isEqualTo("f43,f57");
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
        assertThat(doc.path("params")).isEmpty();
    }

    @Test
    void announceSeed_carriesCountAndTemplateParams() throws Exception {
        JsonNode params = docOf("datasource.ANNOUNCE").path("params");

        assertThat(params.path("announceUrl").asText()).isEqualTo("https://ann.example.com/api");
        assertThat(params.path("announcePageSize").asInt()).isEqualTo(5);
        assertThat(params.path("announceDetailUrlTemplate").asText())
                .isEqualTo("https://pdf.example.com/{art_code}.pdf");
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
