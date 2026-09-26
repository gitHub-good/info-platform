package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.RecommendationPersonalizer;
import com.info.platform.application.ai.UserInterestProfile;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationContext;
import com.info.platform.application.recommendation.RecommendationAssociationService.AssociationResult;
import com.info.platform.application.recommendation.RecommendationAssociationService.WatchedSubject;
import com.info.platform.domain.aggregation.Market;
import com.info.platform.domain.aggregation.Subject;
import com.info.platform.domain.aggregation.SubjectCode;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.aggregation.SubjectStatus;
import com.info.platform.domain.aggregation.SubjectType;
import com.info.platform.domain.analysis.Direction;
import com.info.platform.domain.analysis.EventItem;
import com.info.platform.domain.analysis.EventType;
import com.info.platform.domain.analysis.Importance;
import com.info.platform.domain.recommendation.RecLevel;
import com.info.platform.domain.subscription.Subscription;
import com.info.platform.domain.subscription.SubscriptionChannel;
import com.info.platform.domain.subscription.SubscriptionRepository;
import com.info.platform.domain.subscription.SubscriptionStatus;
import com.info.platform.domain.subscription.SubscriptionType;
import com.info.platform.domain.subscription.Watchlist;
import com.info.platform.domain.subscription.WatchlistItem;
import com.info.platform.domain.subscription.WatchlistRepository;
import com.info.platform.domain.subscription.WatchlistStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 三级关联引擎单测（T131，方案 §4.4 / ADR-0051 裁决 4）：触发门槛（LOW 拦/无命中空）、P1（自选命中/SUBJECT 订阅并入）、P2 双通道（主题映射 主力/标的行业
 * best-effort/NULL 跳过）、P3（EVENT_TYPE 折算 + 主题词 contains）、多级取最高、P3-only 空标的、标的区 ≤5 截断与热度排序、
 * combo_key、recscore 画像只加权；上下文装配（watchlist/SUBJECT 订阅/行业关注集并集）。 AAA 结构，mock 仓储 + 缺省参数
 * RecommendationSettings。
 */
class RecommendationAssociationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    private WatchlistRepository watchlistRepository;

    private SubscriptionRepository subscriptionRepository;

    private SubjectRepository subjectRepository;

    private RecommendationPersonalizer personalizer;

    private RecommendationAssociationService service;

    @BeforeEach
    void setUp() {
        watchlistRepository = mock(WatchlistRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        personalizer = mock(RecommendationPersonalizer.class);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        when(personalizer.buildProfile(anyLong())).thenReturn(UserInterestProfile.EMPTY);
        service =
                new RecommendationAssociationService(
                        watchlistRepository,
                        subscriptionRepository,
                        subjectRepository,
                        personalizer,
                        new RecommendationSettings(configService, new ObjectMapper()));
    }

    // ---- 事件与上下文工厂 ----

    private static EventItem event(
            EventType type,
            Importance importance,
            List<String> industries,
            List<EventItem.SubjectRef> subjects,
            String summary) {
        return EventItem.create(
                9001L,
                type,
                summary,
                industries,
                Direction.BULLISH,
                importance,
                List.of(),
                subjects,
                null,
                NOW,
                "2026-09-22",
                "v1.0");
    }

    private static AssociationContext context(
            List<WatchedSubject> watchSubjects,
            Set<String> themes,
            List<String> eventTypeSubKeys,
            Set<String> industryInterest,
            UserInterestProfile profile) {
        return new AssociationContext(
                7L, watchSubjects, themes, eventTypeSubKeys, industryInterest, profile);
    }

    private static WatchedSubject watched(String code, String name, String industry) {
        return new WatchedSubject(null, code, name, industry);
    }

    // ---- 触发门槛 ----

    @Test
    void associate_lowImportance_neverTriggers() {
        EventItem low =
                event(EventType.POLICY_RELEASE, Importance.LOW, List.of("电子"), List.of(), "摘要");

        assertThat(
                        service.associate(
                                low,
                                "标题",
                                context(
                                        List.of(),
                                        Set.of(),
                                        List.of(),
                                        Set.of(),
                                        UserInterestProfile.EMPTY)))
                .isEmpty();
    }

    @Test
    void associate_mediumNoHit_empty() {
        EventItem medium =
                event(
                        EventType.POLICY_RELEASE,
                        Importance.MEDIUM,
                        List.of("钢铁"),
                        List.of(),
                        "与用户无关的摘要");

        assertThat(
                        service.associate(
                                medium,
                                "无关标题",
                                context(
                                        List.of(),
                                        Set.of(),
                                        List.of(),
                                        Set.of(),
                                        UserInterestProfile.EMPTY)))
                .isEmpty();
    }

    // ---- P1 标的直接 ----

    @Test
    void associate_p1WatchlistSubjectHit_directLevel() {
        EventItem evt =
                event(
                        EventType.BUYBACK_CHANGE,
                        Importance.HIGH,
                        List.of("食品饮料"),
                        List.of(new EventItem.SubjectRef("SH600519", "贵州茅台", "食品饮料")),
                        "贵州茅台公告回购计划");
        AssociationContext ctx =
                context(
                        List.of(
                                watched("SH600519", "贵州茅台", null),
                                watched("SZ300750", "宁德时代", null)),
                        Set.of(),
                        List.of(),
                        Set.of(),
                        UserInterestProfile.EMPTY);

        Optional<AssociationResult> result = service.associate(evt, "贵州茅台公告回购", ctx);

        assertThat(result).isPresent();
        AssociationResult association = result.get();
        assertThat(association.level()).isEqualTo(RecLevel.P1);
        assertThat(association.subjects()).hasSize(1);
        assertThat(association.subjects().get(0).code()).isEqualTo("SH600519");
        assertThat(association.subjects().get(0).name()).isEqualTo("贵州茅台");
        assertThat(association.subjects().get(0).inWatchlist()).isTrue();
        // P1 industries = 关联行业上下文（event.affectedIndustries）
        assertThat(association.industries()).containsExactly("食品饮料");
        assertThat(association.comboKey()).isEqualTo("BUYBACK_CHANGE|食品饮料");
        // HIGH × P1 × 无画像 = 6.0
        assertThat(association.recscore()).isCloseTo(6.0, within(1e-9));
    }

    @Test
    void associate_p1SubjectCodeNullOnEvent_skipped() {
        // 未回联标的（code null）不参与 P1 code 判定
        EventItem evt =
                event(
                        EventType.BUYBACK_CHANGE,
                        Importance.HIGH,
                        List.of(),
                        List.of(new EventItem.SubjectRef(null, "贵州茅台", null)),
                        "摘要");
        AssociationContext ctx =
                context(
                        List.of(watched("SH600519", "贵州茅台", null)),
                        Set.of(),
                        List.of(),
                        Set.of(),
                        UserInterestProfile.EMPTY);

        assertThat(service.associate(evt, "标题", ctx)).isEmpty();
    }

    // ---- P2 行业双通道 ----

    @Test
    void associate_p2ChannelA_themeMappedIndustryHit_emptySubjectArea() {
        // 通道 A 主力：订阅「半导体」→ 电子（IndustryDirectory）；通道 B 无数据 → 标的区空（不硬凑，§3.4）
        EventItem evt =
                event(
                        EventType.POLICY_RELEASE,
                        Importance.HIGH,
                        List.of("电子", "计算机"),
                        List.of(),
                        "国家集成电路大基金新增投资");
        AssociationContext ctx =
                context(
                        List.of(),
                        Set.of("半导体"),
                        List.of(),
                        Set.of("电子"),
                        UserInterestProfile.EMPTY);

        Optional<AssociationResult> result = service.associate(evt, "标题", ctx);

        assertThat(result).isPresent();
        AssociationResult association = result.get();
        assertThat(association.level()).isEqualTo(RecLevel.P2);
        assertThat(association.industries()).containsExactly("电子");
        assertThat(association.subjects()).isEmpty();
        assertThat(association.themeHit()).isTrue();
        assertThat(association.matchedTheme()).isEqualTo("半导体");
        // combo_key 取 P2 命中行业首个
        assertThat(association.comboKey()).isEqualTo("POLICY_RELEASE|电子");
        // P2 HIGH × 主题命中 1.15 = 4.6
        assertThat(association.recscore()).isCloseTo(4.6, within(1e-9));
    }

    @Test
    void associate_p2ChannelB_subjectIndustryBestEffort() {
        // 通道 B：自选标的行业非空入集 → 命中；标的区列出该行业命中自选
        EventItem evt =
                event(
                        EventType.REGULATORY_PENALTY,
                        Importance.MEDIUM,
                        List.of("医药生物"),
                        List.of(),
                        "集采政策落地");
        AssociationContext ctx =
                context(
                        List.of(
                                watched("SH600276", "恒瑞医药", "医药生物"),
                                watched("SH600519", "贵州茅台", null)),
                        Set.of(),
                        List.of(),
                        Set.of("医药生物"),
                        UserInterestProfile.EMPTY);

        Optional<AssociationResult> result = service.associate(evt, "标题", ctx);

        assertThat(result).isPresent();
        assertThat(result.get().level()).isEqualTo(RecLevel.P2);
        assertThat(result.get().subjects()).hasSize(1);
        assertThat(result.get().subjects().get(0).code()).isEqualTo("SH600276");
        assertThat(result.get().subjects().get(0).industry()).isEqualTo("医药生物");
    }

    @Test
    void associate_p2ChannelB_nullIndustrySkipped_noError() {
        // 通道 B NULL 行业跳过不阻断：关注集无医药 → 无命中 → 空（该数据状态下走 P3 或不生成）
        EventItem evt =
                event(
                        EventType.REGULATORY_PENALTY,
                        Importance.MEDIUM,
                        List.of("医药生物"),
                        List.of(),
                        "摘要");
        AssociationContext ctx =
                context(
                        List.of(watched("SH600519", "贵州茅台", null)),
                        Set.of(),
                        List.of(),
                        Set.of(),
                        UserInterestProfile.EMPTY);

        assertThat(service.associate(evt, "标题", ctx)).isEmpty();
    }

    // ---- P3 订阅 ----

    @Test
    void associate_p3EventTypeFold_announceCoversCompanyEvents() {
        // subKey 含「公告」→ 公司类 6 类：REGULATORY_PENALTY 命中；POLICY_RELEASE 不在集合
        EventItem penalty =
                event(
                        EventType.REGULATORY_PENALTY,
                        Importance.MEDIUM,
                        List.of(),
                        List.of(),
                        "某公司被立案调查");
        EventItem policy =
                event(EventType.POLICY_RELEASE, Importance.MEDIUM, List.of(), List.of(), "某政策发布");
        AssociationContext ctx =
                context(List.of(), Set.of(), List.of("重大公告"), Set.of(), UserInterestProfile.EMPTY);

        assertThat(service.associate(penalty, "标题", ctx))
                .isPresent()
                .get()
                .extracting(AssociationResult::level)
                .isEqualTo(RecLevel.P3);
        assertThat(service.associate(policy, "标题", ctx)).isEmpty();
    }

    @Test
    void associate_p3EventTypeFold_policyNewsAndEnumNames() {
        // 含「政策」→ POLICY_RELEASE；枚举名 ANNOUNCE/NEWS/POLICY 同折算（沿 FeedMatcher 口径）
        AssociationContext policyCtx =
                context(List.of(), Set.of(), List.of("政策动态"), Set.of(), UserInterestProfile.EMPTY);
        EventItem policy =
                event(EventType.POLICY_RELEASE, Importance.MEDIUM, List.of(), List.of(), "摘要");
        assertThat(service.associate(policy, "标题", policyCtx)).isPresent();

        AssociationContext newsCtx =
                context(List.of(), Set.of(), List.of("NEWS"), Set.of(), UserInterestProfile.EMPTY);
        EventItem exec =
                event(EventType.EXEC_CHANGE, Importance.MEDIUM, List.of(), List.of(), "摘要");
        assertThat(service.associate(exec, "标题", newsCtx)).isPresent();

        AssociationContext announceCtx =
                context(
                        List.of(),
                        Set.of(),
                        List.of("ANNOUNCE"),
                        Set.of(),
                        UserInterestProfile.EMPTY);
        EventItem forecast =
                event(EventType.EARNINGS_FORECAST, Importance.MEDIUM, List.of(), List.of(), "摘要");
        assertThat(service.associate(forecast, "标题", announceCtx)).isPresent();

        AssociationContext techCtx =
                context(List.of(), Set.of(), List.of("公告"), Set.of(), UserInterestProfile.EMPTY);
        EventItem tech =
                event(EventType.TECH_BREAKTHROUGH, Importance.MEDIUM, List.of(), List.of(), "摘要");
        assertThat(service.associate(tech, "标题", techCtx)).isEmpty(); // TECH 不在公告 6 类
    }

    @Test
    void associate_p3ThemeContainsTitleOrSummary_emptySubjectArea() {
        // 主题词 contains 命中摘要 → P3 空标的（不硬凑，红线）
        EventItem evt =
                event(EventType.OTHER, Importance.MEDIUM, List.of(), List.of(), "美联储宣布下调存款准备金率");
        AssociationContext ctx =
                context(
                        List.of(),
                        Set.of("存款准备金率"),
                        List.of(),
                        Set.of(),
                        UserInterestProfile.EMPTY);

        Optional<AssociationResult> result = service.associate(evt, "美联储动态", ctx);

        assertThat(result).isPresent();
        assertThat(result.get().level()).isEqualTo(RecLevel.P3);
        assertThat(result.get().subjects()).isEmpty();
        assertThat(result.get().matchedTheme()).isEqualTo("存款准备金率");
        // affected 空 → combo_key 尾段 "-"
        assertThat(result.get().comboKey()).isEqualTo("OTHER|-");
        // P3 MEDIUM × 主题 1.15 = 1.15
        assertThat(result.get().recscore()).isCloseTo(1.15, within(1e-9));
    }

    // ---- 多级取最高 / 截断 / 画像 ----

    @Test
    void associate_multiLevelHit_takesHighestP1() {
        // P1 与 P2 双命中 → P1（文案「直接涉及你关注的标的」覆盖）
        EventItem evt =
                event(
                        EventType.MA_MERGER,
                        Importance.HIGH,
                        List.of("电子"),
                        List.of(new EventItem.SubjectRef("SZ300750", "宁德时代", "电力设备")),
                        "摘要");
        AssociationContext ctx =
                context(
                        List.of(watched("SZ300750", "宁德时代", "电力设备")),
                        Set.of("半导体"),
                        List.of("新闻"),
                        Set.of("电子"),
                        UserInterestProfile.EMPTY);

        Optional<AssociationResult> result = service.associate(evt, "标题", ctx);

        assertThat(result).isPresent();
        assertThat(result.get().level()).isEqualTo(RecLevel.P1);
    }

    @Test
    void associate_subjectAreaTruncatedToLimit_heatDescOrder() {
        // P1 命中 7 只 → 截 5；热度降序（heat 4.0 > 3.0 > 2.0 > 1.0 > 0）
        List<EventItem.SubjectRef> refs = new java.util.ArrayList<>();
        List<WatchedSubject> watch = new java.util.ArrayList<>();
        List<UserInterestProfile.SubjectReadStat> stats = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String code = "SH60000" + i;
            refs.add(new EventItem.SubjectRef(code, "公司" + i, "食品饮料"));
            watch.add(watched(code, "公司" + i, null));
            stats.add(
                    new UserInterestProfile.SubjectReadStat(
                            (long) i,
                            code,
                            "公司" + i,
                            1,
                            LocalDate.parse("2026-09-21"),
                            i == 7 ? 4.0 : 0.0));
        }
        // 热度：公司 7 = 4.0，公司 1 = 1.0，其余 0——期望截断后首为公司 7、次为公司 1
        stats.set(
                0,
                new UserInterestProfile.SubjectReadStat(
                        1L, "SH600001", "公司1", 1, LocalDate.parse("2026-09-21"), 1.0));
        EventItem evt =
                event(EventType.EARNINGS_FORECAST, Importance.HIGH, List.of("食品饮料"), refs, "摘要");
        AssociationContext ctx =
                context(
                        watch,
                        Set.of(),
                        List.of(),
                        Set.of(),
                        new UserInterestProfile(List.of(), List.of(), stats));

        Optional<AssociationResult> result = service.associate(evt, "标题", ctx);

        assertThat(result).isPresent();
        assertThat(result.get().subjects()).hasSize(5);
        assertThat(result.get().subjects().get(0).code()).isEqualTo("SH600007"); // heat 4.0
        assertThat(result.get().subjects().get(1).code()).isEqualTo("SH600001"); // heat 1.0
        // recscore 用标的区最大热度 4.0 → norm 0.8 → P1 HIGH 6×1.2 = 7.2
        assertThat(result.get().recscore()).isCloseTo(7.2, within(1e-9));
    }

    // ---- 上下文装配 ----

    private static Watchlist watchlistOf(long id, long userId, Long... subjectIds) {
        List<WatchlistItem> items = new java.util.ArrayList<>();
        for (Long subjectId : subjectIds) {
            items.add(
                    WatchlistItem.reconstruct(
                            subjectId * 10,
                            id,
                            subjectId,
                            WatchlistItem.DEFAULT_THRESHOLD,
                            WatchlistStatus.ENABLED,
                            0,
                            NOW,
                            NOW));
        }
        return Watchlist.reconstruct(
                id, userId, "清单" + id, null, WatchlistStatus.ENABLED, items, 0, NOW, NOW);
    }

    private static Subscription subscription(SubscriptionType type, String subKey, boolean active) {
        return Subscription.reconstruct(
                null,
                7L,
                type,
                subKey,
                SubscriptionChannel.IN_APP,
                active ? SubscriptionStatus.SUBSCRIBED : SubscriptionStatus.UNSUBSCRIBED,
                0,
                NOW,
                NOW);
    }

    private static Subject subjectOf(long id, String code, String name, String industry) {
        return Subject.reconstruct(
                id,
                SubjectCode.of(code),
                Market.A_SHARE,
                SubjectType.STOCK,
                name,
                null,
                industry,
                SubjectStatus.ENABLED,
                0,
                NOW,
                NOW);
    }

    @Test
    void buildContext_unionsWatchlistAndSubjectSubscription_resolvesIndustryAndThemes() {
        // Arrange：自选 1 标的（行业 null）+ SUBJECT 订阅 1 标的（行业 电子）+ 主题「半导体」+ 退订订阅排除
        when(watchlistRepository.findAllByOwnerId(7L))
                .thenReturn(List.of(watchlistOf(1L, 7L, 101L)));
        when(subscriptionRepository.findByOwnerIdCursor(7L, null, null, 200))
                .thenReturn(
                        List.of(
                                subscription(SubscriptionType.SUBJECT, "202", true),
                                subscription(SubscriptionType.TOPIC, "半导体", true),
                                subscription(SubscriptionType.POLICY_THEME, "货币政策", true),
                                subscription(SubscriptionType.TOPIC, "已退订主题", false)));
        when(subjectRepository.findAllById(any()))
                .thenReturn(
                        List.of(
                                subjectOf(101L, "SH600519", "贵州茅台", null),
                                subjectOf(202L, "SZ300750", "宁德时代", "电子")));

        // Act
        AssociationContext ctx = service.buildContext(7L);

        // Assert：P1 判定集 = 自选 ∪ SUBJECT 订阅
        assertThat(ctx.watchSubjects()).hasSize(2);
        assertThat(ctx.watchSubjects().stream().map(WatchedSubject::code))
                .containsExactlyInAnyOrder("SH600519", "SZ300750");
        // 主题词：活跃 TOPIC/POLICY_THEME；退订排除；「货币政策」无映射不入行业集（走 P3）
        assertThat(ctx.themeKeywords()).containsExactlyInAnyOrder("半导体", "货币政策");
        // 行业关注集：通道 A（半导体→电子）∪ 通道 B（宁德时代 行业 电子；贵州茅台 NULL 跳过）
        assertThat(ctx.industryInterest()).containsExactly("电子");
    }

    @Test
    void buildContext_ignoresDeletedSubjectAndBrokenSubKey() {
        // 已删标的（仓储不返回）与非数字 SUBJECT subKey 跳过不阻断
        when(watchlistRepository.findAllByOwnerId(7L)).thenReturn(List.of());
        when(subscriptionRepository.findByOwnerIdCursor(7L, null, null, 200))
                .thenReturn(
                        List.of(
                                subscription(SubscriptionType.SUBJECT, "not-a-number", true),
                                subscription(SubscriptionType.EVENT_TYPE, "公告", true)));
        when(subjectRepository.findAllById(any())).thenReturn(List.of());

        AssociationContext ctx = service.buildContext(7L);

        assertThat(ctx.watchSubjects()).isEmpty();
        assertThat(ctx.eventTypeSubKeys()).containsExactly("公告");
        assertThat(ctx.industryInterest()).isEmpty();
    }
}
