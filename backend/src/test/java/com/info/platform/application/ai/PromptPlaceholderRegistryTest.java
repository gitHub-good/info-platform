package com.info.platform.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.info.platform.application.aggregation.SubjectDetail;
import com.info.platform.application.policy.PolicyTendencyService;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.BriefContentCodec;
import com.info.platform.domain.ai.BriefType;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.ai.PlaceholderDescriptor;
import com.info.platform.domain.policy.PolicyRepository;
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
                                    Clock.systemUTC()),
                            new PolicyTendencyService(
                                    mock(PolicyRepository.class),
                                    mock(LlmGateway.class),
                                    mock(PromptTemplateService.class),
                                    mock(BriefContentCodec.class))));

    @Test
    void aggregates_fourScenarios_withExpectedCounts() {
        // Act + Assert：首版注册表 30 键 = 17（场景1）+ 17（场景2 共用）+ 7（场景3）+ 6（场景4）
        assertThat(registry.byBriefType(BriefType.STOCK)).hasSize(17);
        assertThat(registry.byBriefType(BriefType.EVENT_ATTRIBUTION)).hasSize(17);
        assertThat(registry.byBriefType(BriefType.POLICY)).hasSize(7);
        assertThat(registry.byBriefType(BriefType.DAILY_RECOMMEND)).hasSize(6);
        assertThat(registry.all()).containsOnlyKeys(BriefType.values());
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
                        List.of(),
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
                        Clock.systemUTC());

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
