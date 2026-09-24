package com.info.platform.infrastructure.aggregation;

import com.info.platform.domain.aggregation.SourceAdapter;
import com.info.platform.domain.aggregation.SourceCode;
import com.info.platform.domain.push.AnomalyRepository;
import com.info.platform.infrastructure.common.CircuitBreaker;
import com.info.platform.infrastructure.common.ConfigCenter;
import com.info.platform.infrastructure.common.ResilienceRunner;
import com.info.platform.infrastructure.common.SourceCache;
import java.util.List;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源 adapter 热路由装配（T36 / ADR-0017 冲突解法 1）。
 *
 * <p>改造前：7 对 real/mock adapter 以 {@code adapter.mock.enabled} {@code @ConditionalOnProperty}
 * 启动期互斥装配。改造后：14 个实现全部经本配置注册为<b>内部 bean</b>（非自动装配候选，见 {@link #internalAdapterNonCandidateMarker}），
 * 每源一个 {@link RoutingSourceAdapter} 作为该源唯一对外 {@link SourceAdapter}——消费方（{@code AggregationService}
 * 等注入 {@code List<SourceAdapter>}）只见 7 个路由 bean，既有代码零改动。
 *
 * <p>yml {@code adapter.mock.enabled} 降级为分源 mode 的种子默认值（true → 各源初始 MOCK， 见 {@code
 * DataSourceRuntimeConfigSeeder}），测试 profile 语义平移。
 */
@Configuration
public class SourceAdapterRoutingConfig {

    /** 内部 adapter bean 名（{@code @Bean} 方法名；标记非候选防其进入 List&lt;SourceAdapter&gt; 注入）。 */
    private static final List<String> INTERNAL_ADAPTER_BEANS =
            List.of(
                    "quoteSourceAdapter",
                    "mockQuoteSourceAdapter",
                    "financeSourceAdapter",
                    "mockFinanceSourceAdapter",
                    "valuationSourceAdapter",
                    "mockValuationSourceAdapter",
                    "announceSourceAdapter",
                    "mockAnnounceSourceAdapter",
                    "newsSourceAdapter",
                    "mockNewsSourceAdapter",
                    "policySourceAdapter",
                    "mockPolicySourceAdapter",
                    "eventSourceAdapter",
                    "mockEventSourceAdapter");

    /**
     * 把 14 个内部 adapter bean 标记为非自动装配候选：按类型注入（如 {@code List<SourceAdapter>}）不可见， 仅本配置内直接方法调用引用（CGLIB
     * 代理保证单例）。缺失 bean 名即装配面错误，fail-fast。
     */
    @Bean
    public static BeanFactoryPostProcessor internalAdapterNonCandidateMarker() {
        return beanFactory -> {
            for (String name : INTERNAL_ADAPTER_BEANS) {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                definition.setAutowireCandidate(false);
            }
        };
    }

    // —— 7 个路由 bean：每源唯一对外 SourceAdapter ——

    @Bean
    public SourceAdapter quoteRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            TencentQuoteClient tencentClient,
            ConfigCenter configCenter,
            @org.springframework.beans.factory.annotation.Value("${adapter.quote-source:auto}")
                    String quoteSourceMode) {
        return new RoutingSourceAdapter(
                SourceCode.QUOTE,
                quoteSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        client,
                        tencentClient,
                        quoteSourceMode),
                mockQuoteSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter financeRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyFinanceClient client,
            ConfigCenter configCenter) {
        return new RoutingSourceAdapter(
                SourceCode.FINANCE,
                financeSourceAdapter(cache, fieldMapper, runner, breaker, client),
                mockFinanceSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter valuationRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            TencentQuoteClient tencentClient,
            ConfigCenter configCenter,
            @org.springframework.beans.factory.annotation.Value("${adapter.valuation-source:auto}")
                    String valuationSourceMode) {
        return new RoutingSourceAdapter(
                SourceCode.VALUATION,
                valuationSourceAdapter(
                        cache,
                        fieldMapper,
                        runner,
                        breaker,
                        client,
                        tencentClient,
                        valuationSourceMode),
                mockValuationSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter announceRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyAnnounceClient client,
            ConfigCenter configCenter) {
        return new RoutingSourceAdapter(
                SourceCode.ANNOUNCE,
                announceSourceAdapter(cache, fieldMapper, runner, breaker, client),
                mockAnnounceSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter newsRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            SinaNewsClient client,
            ConfigCenter configCenter) {
        return new RoutingSourceAdapter(
                SourceCode.NEWS,
                newsSourceAdapter(cache, fieldMapper, runner, breaker, client),
                mockNewsSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter policyRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            GovPolicyClient client,
            ConfigCenter configCenter) {
        return new RoutingSourceAdapter(
                SourceCode.POLICY,
                policySourceAdapter(cache, fieldMapper, runner, breaker, client),
                mockPolicySourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    @Bean
    public SourceAdapter eventRoutingSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            AnomalyRepository anomalyRepository,
            ConfigCenter configCenter) {
        return new RoutingSourceAdapter(
                SourceCode.EVENT,
                eventSourceAdapter(cache, fieldMapper, runner, breaker, anomalyRepository),
                mockEventSourceAdapter(cache, fieldMapper, runner, breaker),
                configCenter);
    }

    // —— 14 个内部 adapter bean（真实 7 + mock 7；直接方法调用引用，不进类型注入候选） ——

    @Bean
    QuoteSourceAdapter quoteSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            TencentQuoteClient tencentClient,
            @org.springframework.beans.factory.annotation.Value("${adapter.quote-source:auto}")
                    String quoteSourceMode) {
        return new QuoteSourceAdapter(
                cache, fieldMapper, runner, breaker, client, tencentClient, quoteSourceMode);
    }

    @Bean
    MockQuoteSourceAdapter mockQuoteSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockQuoteSourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    FinanceSourceAdapter financeSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyFinanceClient client) {
        return new FinanceSourceAdapter(cache, fieldMapper, runner, breaker, client);
    }

    @Bean
    MockFinanceSourceAdapter mockFinanceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockFinanceSourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    ValuationSourceAdapter valuationSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyClient client,
            TencentQuoteClient tencentClient,
            @org.springframework.beans.factory.annotation.Value("${adapter.valuation-source:auto}")
                    String valuationSourceMode) {
        return new ValuationSourceAdapter(
                cache, fieldMapper, runner, breaker, client, tencentClient, valuationSourceMode);
    }

    @Bean
    MockValuationSourceAdapter mockValuationSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockValuationSourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    AnnounceSourceAdapter announceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            EastMoneyAnnounceClient client) {
        return new AnnounceSourceAdapter(cache, fieldMapper, runner, breaker, client);
    }

    @Bean
    MockAnnounceSourceAdapter mockAnnounceSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockAnnounceSourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    NewsSourceAdapter newsSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            SinaNewsClient client) {
        return new NewsSourceAdapter(cache, fieldMapper, runner, breaker, client);
    }

    @Bean
    MockNewsSourceAdapter mockNewsSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockNewsSourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    PolicySourceAdapter policySourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            GovPolicyClient client) {
        return new PolicySourceAdapter(cache, fieldMapper, runner, breaker, client);
    }

    @Bean
    MockPolicySourceAdapter mockPolicySourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockPolicySourceAdapter(cache, fieldMapper, runner, breaker);
    }

    @Bean
    EventSourceAdapter eventSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker,
            AnomalyRepository anomalyRepository) {
        return new EventSourceAdapter(cache, fieldMapper, runner, breaker, anomalyRepository);
    }

    @Bean
    MockEventSourceAdapter mockEventSourceAdapter(
            SourceCache cache,
            FieldMapper fieldMapper,
            ResilienceRunner runner,
            CircuitBreaker breaker) {
        return new MockEventSourceAdapter(cache, fieldMapper, runner, breaker);
    }
}
