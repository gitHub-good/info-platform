package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.subscription.WatchlistRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PromptPlaceholderRegistry 单测（T46 / ADR-0022）：聚合完整性 + 同源防漂移闸门。
 *
 * <p>同源闸门：代表性满样本输入下断言各装配器 {@code build()} 输出 keySet == {@code provided()} keySet—— 装配器日后加/删键漏登记描述符
 * → 本测试红（机械兜底，替代人工同步记忆）。
 */
class PromptPlaceholderRegistryTest {

    private static final BriefContextBuilder BRIEF_BUILDER = new BriefContextBuilder();

    private final PromptPlaceholderRegistry registry =
            new PromptPlaceholderRegistry(
                    List.of(
                            BRIEF_BUILDER,
                            new DailyRecommendationContextBuilder(
                                    mock(WatchlistRepository.class),
                                    mock(SubjectRepository.class),
                                    mock(RecommendationPersonalizer.class),
                                    List.of(),
                                    Clock.systemUTC(),
                                    Runnable::run,
                                    2000L),
                            classifyProvider(),
                            extractProvider(),
                            dailyReportProvider(),
                            recommendCardProvider(),
                            weeklyReportProvider(),
                            deepDiveProvider()));

    /** 场景 10（全市场深析）供给方：全部依赖 mock（注册表只读 provided()，不触发调用，M21 T182）。 */
    private static com.info.platform.application.markettop.DeepDiveService deepDiveProvider() {
        return new com.info.platform.application.markettop.DeepDiveService(
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(com.info.platform.domain.markettop.DeepDiveOutputParser.class),
                mock(com.info.platform.application.analysis.PipelineGuardService.class),
                mock(com.info.platform.application.analysis.PipelineSettings.class),
                mock(com.info.platform.application.markettop.MarketTopConfigSettings.class),
                mock(com.info.platform.application.ai.LlmSceneFailureRecorder.class));
    }

    /** 场景 5（行业归类）供给方：全部依赖 mock（注册表只读 provided()，不触发调用）。 */
    private static com.info.platform.application.analysis.ClassificationService classifyProvider() {
        return new com.info.platform.application.analysis.ClassificationService(
                mock(com.info.platform.domain.analysis.NewsAnalysisRepository.class),
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(com.info.platform.application.analysis.SubjectMatcher.class),
                new com.info.platform.application.analysis.PipelineSettings(
                        mock(com.info.platform.application.common.RuntimeConfigService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.systemUTC(),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** 场景 6（事件提取）供给方：全部依赖 mock（注册表只读 provided()，不触发调用）。 */
    private static com.info.platform.application.analysis.EventExtractionService extractProvider() {
        return new com.info.platform.application.analysis.EventExtractionService(
                mock(com.info.platform.domain.analysis.NewsAnalysisRepository.class),
                mock(com.info.platform.domain.analysis.EventItemRepository.class),
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(com.info.platform.application.analysis.SubjectMatcher.class),
                mock(com.info.platform.application.analysis.AiExclusionResolver.class),
                new com.info.platform.application.analysis.PipelineSettings(
                        mock(com.info.platform.application.common.RuntimeConfigService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                Clock.systemUTC(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(com.info.platform.application.analysis.ImpactChainService.class));
    }

    /** 场景 7（行业日报）供给方：全部依赖 mock（注册表只读 provided()，不触发调用，M15 T124）。 */
    private static com.info.platform.application.analysis.DailyReportService dailyReportProvider() {
        return new com.info.platform.application.analysis.DailyReportService(
                mock(com.info.platform.domain.analysis.DailyReportRepository.class),
                mock(com.info.platform.domain.analysis.HeatSnapshotRepository.class),
                mock(com.info.platform.application.analysis.PipelineGuardService.class),
                new com.info.platform.application.analysis.PipelineSettings(
                        mock(com.info.platform.application.common.RuntimeConfigService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.systemUTC());
    }

    /** 场景 9（行业周报）供给方：全部依赖 mock（注册表只读 provided()，不触发调用，M17 T145）。 */
    private static com.info.platform.application.analysis.WeeklyReportService
            weeklyReportProvider() {
        return new com.info.platform.application.analysis.WeeklyReportService(
                mock(com.info.platform.domain.analysis.WeeklyReportRepository.class),
                mock(com.info.platform.domain.analysis.DailyReportRepository.class),
                mock(com.info.platform.domain.analysis.HeatSnapshotRepository.class),
                mock(com.info.platform.application.analysis.PipelineGuardService.class),
                new com.info.platform.application.analysis.PipelineSettings(
                        mock(com.info.platform.application.common.RuntimeConfigService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.systemUTC());
    }

    /** 场景 8（推荐卡片逻辑链）供给方：全部依赖 mock（注册表只读 provided()，不触发调用，M16 T132）。 */
    private static com.info.platform.application.recommendation.RecommendationCardService
            recommendCardProvider() {
        return new com.info.platform.application.recommendation.RecommendationCardService(
                mock(com.info.platform.domain.recommendation.RecommendationCardRepository.class),
                mock(LlmGateway.class),
                mock(PromptTemplateService.class),
                mock(com.info.platform.application.analysis.PipelineGuardService.class),
                mock(SubjectRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void aggregates_sevenScenarios_withExpectedCounts() {
        // Act + Assert：注册表 38 键 = 17（场景1）+ 17（场景2 共用）+ 0（场景3 政策解读，T203 退役）+ 6（场景4）+ 2（场景5 行业归类，M15
        // T121）
        // + 3（场景6 事件提取 today/batchSize/items，M15 T122）+ 3（场景7 行业日报
        // reportDate/industryStats/topEvents，M15 T124）+ 7（场景8 推荐卡片
        // level/eventTypeLabel/directionLabel/summary/industries/subjects/watchSubjects，M16 T132）
        assertThat(registry.byBriefType(BriefType.STOCK)).hasSize(17);
        assertThat(registry.byBriefType(BriefType.EVENT_ATTRIBUTION)).hasSize(17);
        // V2.3-M23 T203：PolicyTendencyService 退役——POLICY 场景（政策解读模板，DB 行冻结留档）供给方清零；
        // 无供给方时注册表返回空列表（差集基准空集 = 全部 unknown，防御语义），模板编辑器该组对照区为空
        assertThat(registry.byBriefType(BriefType.POLICY)).isEmpty();
        assertThat(registry.byBriefType(BriefType.DAILY_RECOMMEND)).hasSize(6);
        assertThat(registry.byBriefType(BriefType.L1_CLASSIFY)).hasSize(2);
        assertThat(registry.byBriefType(BriefType.L2_EXTRACT)).hasSize(3);
        assertThat(registry.byBriefType(BriefType.INDUSTRY_DAILY)).hasSize(3);
        assertThat(registry.byBriefType(BriefType.RECOMMEND_CARD)).hasSize(7);
        // 场景 9 行业周报 6 键（weekStart/weekEnd/heatStats/topEvents/policyLines/trendSignals，M17 T145）
        assertThat(registry.byBriefType(BriefType.INDUSTRY_WEEKLY)).hasSize(6);
        // 场景 10 全市场深析 9 键（subject/factors/totalScore/percentile/breakthrough/topEvents/relatedNews/
        // industryNews/marketSnapshot，M21 T182——与 DeepDivePromptComposer 同源）
        assertThat(registry.byBriefType(BriefType.DEEP_DIVE)).hasSize(9);
        // T203：POLICY 供给方退役 → all() 键集少 POLICY（EnumMap 只收录有供给方的场景）；
        // 只读接口按 BriefType.values() 遍历组装分组，POLICY 组以空列表呈现（byBriefType 缺省防御）
        assertThat(registry.all())
                .containsOnlyKeys(
                        BriefType.STOCK,
                        BriefType.EVENT_ATTRIBUTION,
                        BriefType.DAILY_RECOMMEND,
                        BriefType.L1_CLASSIFY,
                        BriefType.L2_EXTRACT,
                        BriefType.INDUSTRY_DAILY,
                        BriefType.RECOMMEND_CARD,
                        BriefType.INDUSTRY_WEEKLY,
                        BriefType.DEEP_DIVE);
    }

    @Test
    void briefContextBuilder_sameSource_buildKeySetEqualsProvidedKeySet() {
        // Arrange：六分区满样本（subject/行情/财务/估值/公告/新闻全给值，17 键全注入）
        SubjectDetail fullDetail =
                new SubjectDetail(
                        new SubjectDetail.SubjectInfo("SH600519", "贵州茅台", "A_SHARE", 1, "白酒"),
                        Map.of("price", 1680.0, "changePct", 1.2, "preClose", 1660.0),
                        Map.of("reportDate", "2025-12-31", "roe", "30.55"),
                        Map.of("peTtm", 25.0, "pb", 9.5),
                        List.of(Map.of("title", "年报", "url", "http://a")),
                        List.of(Map.of("title", "新闻", "url", "http://n")),
                        null,
                        List.of(),
                        Map.of());

        // Act
        Map<String, String> ctx = BRIEF_BUILDER.build(fullDetail, BriefType.STOCK);

        // Assert：build() 实际注入键集 == 注册表描述符键集（且保序一致，便于对照区与 ctx.put 对齐维护）
        assertThat(ctx.keySet())
                .containsExactlyElementsOf(keys(BRIEF_BUILDER.provided()))
                .hasSize(17);
    }

    @Test
    void dailyRecommendationBuilder_sameSource_buildContextKeySetEqualsProvidedKeySet() {
        // Arrange：空自选池 + 空画像（最简样本下 6 键仍全注入，poolSize=0 其余「暂无」）
        DailyRecommendationContextBuilder builder =
                new DailyRecommendationContextBuilder(
                        stubWatchlistRepo(),
                        mock(SubjectRepository.class),
                        stubPersonalizer(),
                        List.of(),
                        Clock.systemUTC(),
                        Runnable::run,
                        2000L);

        // Act + Assert：buildContext 输出键集 == provided 键集
        assertThat(builder.buildContext(1L).keySet())
                .containsExactlyElementsOf(keys(builder.provided()))
                .hasSize(6);
    }

    @Test
    void provided_descriptionsAreNonBlank() {
        // Assert：描述符均带一句话说明（T46 交付文案要求，防占位描述漏写）
        for (BriefType type : BriefType.values()) {
            for (PlaceholderDescriptor d : registry.byBriefType(type)) {
                assertThat(d.key()).isNotBlank();
                assertThat(d.description()).as("key=%s", d.key()).isNotBlank();
            }
        }
    }

    private static List<String> keys(List<PlaceholderDescriptor> descriptors) {
        return descriptors.stream().map(PlaceholderDescriptor::key).toList();
    }

    private static WatchlistRepository stubWatchlistRepo() {
        WatchlistRepository repo = mock(WatchlistRepository.class);
        when(repo.findAllByOwnerId(1L)).thenReturn(List.of());
        return repo;
    }

    private static RecommendationPersonalizer stubPersonalizer() {
        RecommendationPersonalizer personalizer = mock(RecommendationPersonalizer.class);
        when(personalizer.buildProfile(1L)).thenReturn(UserInterestProfile.EMPTY);
        return personalizer;
    }
}
