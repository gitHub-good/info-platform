package com.info.platform.infrastructure.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.common.RuntimeConfig;
import com.info.platform.domain.common.RuntimeConfigRepository;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.infrastructure.common.AggregationRuntimeSettingsImpl;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.SourceAdapterInfrastructureConfig;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * {@link SourceAdapterRoutingConfig} 装配测试（T36）：14 adapter 无条件装配为内部 bean（非自动装配候选）， 每源唯一对外 {@link
 * SourceAdapter} 为 RoutingSourceAdapter——注入 {@code List<SourceAdapter>} 的消费方（AggregationService 等）
 * 恰见 7 个、sourceCode 无重复（对齐方案 §4.3「AggregationService 零改动」）。 mock/real 双注册后上下文行为由 datasource.{CODE}
 * 种子/缺省决定，不再由条件装配决定。
 */
class SourceAdapterRoutingConfigTest {

    /** 消费方替身：按 AggregationService 同款注入方式收 List&lt;SourceAdapter&gt;（构造注入）。 */
    static class AdapterConsumer {
        final List<SourceAdapter> adapters;

        AdapterConsumer(List<SourceAdapter> adapters) {
            this.adapters = adapters;
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        RuntimeConfigRepository inMemoryRepository() {
            return new InMemoryRepository();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        com.info.platform.application.common.RuntimeConfigService runtimeConfigService(
                RuntimeConfigRepository repository, ObjectMapper objectMapper) {
            return new com.info.platform.application.common.RuntimeConfigService(
                    repository, List.of(), event -> {}, clock(), objectMapper);
        }

        @Bean
        ConfigCenter configCenter(RuntimeConfigService service, ObjectMapper objectMapper) {
            return new ConfigCenter(service, List.of(), null, objectMapper);
        }

        @Bean
        AnomalyRepository anomalyRepository() {
            return org.mockito.Mockito.mock(AnomalyRepository.class);
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        AggregationRuntimeSettingsImpl aggregationRuntimeSettings(ConfigCenter configCenter) {
            return new AggregationRuntimeSettingsImpl(configCenter);
        }

        @Bean
        AdapterConsumer adapterConsumer(List<SourceAdapter> adapters) {
            return new AdapterConsumer(adapters);
        }
    }

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

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .withUserConfiguration(
                            TestConfig.class,
                            SourceAdapterInfrastructureConfig.class,
                            SourceAdapterRoutingConfig.class,
                            EastMoneyClient.class,
                            TencentQuoteClient.class,
                            EastMoneyFinanceClient.class,
                            SinaFinanceClient.class,
                            EastMoneyAnnounceClient.class,
                            CninfoAnnounceClient.class,
                            SinaNewsClient.class,
                            GovPolicyClient.class);

    @Test
    void listInjection_seesExactlySevenRoutingAdapters_uniqueSourceCodes() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    List<SourceAdapter> adapters = context.getBean(AdapterConsumer.class).adapters;
                    assertThat(adapters).hasSize(7);
                    long distinctCodes =
                            adapters.stream().map(SourceAdapter::sourceCode).distinct().count();
                    assertThat(distinctCodes).isEqualTo(7);
                    assertThat(adapters)
                            .allMatch(adapter -> adapter instanceof RoutingSourceAdapter);
                });
    }

    @Test
    void internalAdapters_presentAsBeans_butNotRoutingBeans() {
        runner.run(
                context -> {
                    assertThat(context).hasBean("quoteSourceAdapter");
                    assertThat(context).hasBean("mockQuoteSourceAdapter");
                    assertThat(context).hasBean("mockEventSourceAdapter");
                    // 7 个路由 bean 以 SourceAdapter 类型对外
                    assertThat(context).hasBean("quoteRoutingSourceAdapter");
                    assertThat(context).hasBean("eventRoutingSourceAdapter");
                    RoutingSourceAdapter routing =
                            (RoutingSourceAdapter)
                                    context.getBean(
                                            "quoteRoutingSourceAdapter", SourceAdapter.class);
                    assertThat(routing.sourceCode()).isEqualTo(SourceCode.QUOTE);
                });
    }

    @Test
    void supportedSubjectTypes_stockOnlySourcesFollowRealAdapterDeclaration() {
        runner.run(
                context -> {
                    SourceAdapter valuation =
                            context.getBean("valuationRoutingSourceAdapter", SourceAdapter.class);
                    SourceAdapter quote =
                            context.getBean("quoteRoutingSourceAdapter", SourceAdapter.class);
                    // T31 声明语义平移：估值/财务仅股票，行情全类型
                    assertThat(valuation.supportedSubjectTypes())
                            .containsExactly(
                                    com.info.platform.domain.aggregation.SubjectType.STOCK);
                    assertThat(quote.supportedSubjectTypes())
                            .containsAnyElementsOf(
                                    List.of(
                                            com.info.platform.domain.aggregation.SubjectType
                                                    .STOCK));
                });
    }
}
