package com.info.platform.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.ai.PromptTemplateService;
import com.info.platform.application.analysis.PipelineGuardService;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.push.PushService;
import com.info.platform.domain.aggregation.SubjectRepository;
import com.info.platform.domain.ai.LlmException;
import com.info.platform.domain.ai.LlmGateway;
import com.info.platform.domain.common.UserRepository;
import com.info.platform.domain.recommendation.RecommendationCardRepository;
import com.info.platform.domain.recommendation.RecommendationMuteRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 口径 A 时延断言用例（M17 T147 / GAP-01，M16 测试报告 §5——「缺 ≤5min 自动断言用例」补齐）：事件落库 → FEED 消费 → 建卡 → 推送闸门全链（真实仓储
 * + 真实关联/建卡/闸门服务装配，LLM Mock 走模板卡），固定时钟推进 2 分钟（Mock 时钟语义），断言 {@code recommendation_card.pushed_at −
 * event_item.created_at ≤ 5min}——口径 A 承诺的机制化防线，不再依赖现场样本。
 */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationFeedLatencyTest {

    /** 事件落库时刻（模拟 L2 产事件）。 */
    private static final Instant EVENT_AT = Instant.parse("2026-09-22T08:00:00Z");

    /** FEED tick 执行时刻（落库后 2 分钟——20s 消费缓冲已过）。 */
    private static final Instant TICK_AT = EVENT_AT.plusSeconds(120);

    /** 口径 A 时延上限（5 分钟）。 */
    private static final Duration LATENCY_BOUND = Duration.ofMinutes(5);

    private static final long USER_ID = 9471L;

    @Autowired private UserRepository userRepository;

    @Autowired private RecommendationAssociationService associationService;

    @Autowired private RecommendationCardRepository cardRepository;

    @Autowired private RecommendationMuteRepository muteRepository;

    @Autowired private PromptTemplateService promptTemplateService;

    @Autowired private PipelineGuardService guardService;

    @Autowired private SubjectRepository subjectRepository;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed() {
        cleanup();
        jdbcTemplate.update(
                """
                INSERT INTO user (id, username, password_hash, created_at, updated_at, version)
                VALUES (?, 't147lat', 'x', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)
                """,
                USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO subject_master (id, subject_code, market, subject_type, name, industry,
                                            status, created_at, updated_at, version)
                VALUES (?, 'T147LAT', 'A_SHARE', 1, '时延断言标的', '银行', 1,
                        '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)
                """,
                USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO watchlist (id, user_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, '时延断言清单', 1, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)
                """,
                USER_ID,
                USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO watchlist_item (watchlist_id, subject_id, status, created_at, updated_at, version)
                VALUES (?, ?, 1, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', 0)
                """,
                USER_ID,
                USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO info_source (id, source_code, name, category, adapter_type, endpoint,
                                         created_at, updated_at)
                VALUES (9471, 't147lat_src', 'T147LAT源', '快讯', 'rss', 'https://example.com/rss',
                        '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                """);
        // 事件落库（subjects 含自选标的 code → P1 直接命中；created_at = 事件落库时刻）
        jdbcTemplate.update(
                """
                INSERT INTO news_item (id, source_id, title, published_at, fetched_at, fingerprint,
                                       created_at, updated_at)
                VALUES (9471, 9471, '时延断言原文', '2026-09-22T07:58:00Z', '2026-09-22T07:59:00Z',
                        'fp-t147lat', '2026-09-22T07:59:00Z', '2026-09-22T07:59:00Z')
                """);
        jdbcTemplate.update(
                """
                INSERT INTO event_item (id, news_id, event_type, summary, affected_industries,
                                        direction, importance, subjects, event_date, created_at, updated_at)
                VALUES (9471, 9471, 'POLICY_RELEASE', '时延断言事件', '["银行"]', 'BULLISH', 'HIGH',
                        '[{"code":"T147LAT","name":"时延断言标的","industry":"银行"}]',
                        '2026-09-22', ?, ?)
                """,
                EVENT_AT.toString(),
                EVENT_AT.toString());
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM recommendation_card WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM event_item WHERE id = 9471");
        jdbcTemplate.update("DELETE FROM news_item WHERE id = 9471");
        jdbcTemplate.update("DELETE FROM info_source WHERE id = 9471");
        jdbcTemplate.update("DELETE FROM watchlist_item WHERE watchlist_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM watchlist WHERE id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM subject_master WHERE id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM user WHERE id = ?", USER_ID);
    }

    @Test
    @DisplayName("事件落库 → FEED tick → 推送：pushed_at − event.created_at ≤ 5min（口径 A 断言在册）")
    void eventToPush_latencyWithinBound() {
        // 装配：真实仓储/关联/建卡/闸门 + LLM Mock（模板卡）+ PushService Mock + 固定时钟（TICK_AT = 落库 +2min）
        LlmGateway llmGateway = mock(LlmGateway.class);
        when(llmGateway.chat(any()))
                .thenThrow(new LlmException("测试走模板卡", java.util.List.of("deepseek"), null));
        RecommendationCardService cardService =
                new RecommendationCardService(
                        cardRepository,
                        llmGateway,
                        promptTemplateService,
                        guardService,
                        subjectRepository,
                        objectMapper);
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(any(String.class))).thenReturn(Optional.empty());
        RecommendationSettings settings = new RecommendationSettings(configService, objectMapper);
        RecommendationPushGate pushGate =
                new RecommendationPushGate(
                        cardRepository,
                        muteRepository,
                        mock(PushService.class),
                        settings,
                        Clock.fixed(TICK_AT, ZoneOffset.UTC));
        RecommendationFeedService feedService =
                new RecommendationFeedService(
                        userRepository,
                        associationService,
                        cardService,
                        pushGate,
                        cardRepository,
                        settings,
                        Clock.fixed(TICK_AT, ZoneOffset.UTC));

        RecommendationFeedService.FeedReport report = feedService.tick();

        // Assert：一事件一卡一推（P1 命中 + 闸门通过）
        assertThat(report.processed()).isGreaterThan(0);
        var pushed =
                jdbcTemplate.queryForMap(
                        "SELECT id, push_status, pushed_at FROM recommendation_card"
                                + " WHERE user_id = ? AND event_id = 9471",
                        USER_ID);
        assertThat(pushed.get("push_status")).isEqualTo("PUSHED");
        Instant pushedAt = Instant.parse(String.valueOf(pushed.get("pushed_at")));
        Instant eventCreatedAt =
                Instant.parse(
                        String.valueOf(
                                jdbcTemplate.queryForObject(
                                        "SELECT created_at FROM event_item WHERE id = 9471",
                                        String.class)));
        Duration latency = Duration.between(eventCreatedAt, pushedAt);
        assertThat(latency)
                .as("口径 A：事件落库 → 推送 ≤5min（实测 %ds）", latency.toSeconds())
                .isLessThanOrEqualTo(LATENCY_BOUND);
    }
}
