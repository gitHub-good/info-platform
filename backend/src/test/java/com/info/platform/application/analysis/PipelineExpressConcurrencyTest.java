package com.info.platform.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.info.platform.application.common.RuntimeConfigService;
import com.info.platform.application.recommendation.RecommendationSettings;
import com.info.platform.domain.analysis.GuardLevel;
import com.info.platform.domain.analysis.NewsAnalysisRepository;
import com.info.platform.domain.feed.AdapterType;
import com.info.platform.domain.feed.InfoSource;
import com.info.platform.domain.feed.InfoSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 快速通道 × 常规批并发幂等集成测试（T131，ADR-0051 裁决 1 / 方案 §3.1「与常规批的并发幂等」）：双 Job 同刻拾取同一 news_id——L0 建行靠
 * UNIQUE(news_id) INSERT OR IGNORE 单行收敛、L1 靠条件 UPDATE 一人胜出（后到者匹配 0 行）。 真实仓储（内存库）+ LLM 协作服务全
 * Mock（零外呼）。 t131e_ 前缀隔离清理。
 */
@SpringBootTest
@ActiveProfiles("test")
class PipelineExpressConcurrencyTest {

    @Autowired private NewsAnalysisRepository repository;

    @Autowired private L0PrefilterService l0Prefilter;

    @Autowired private InfoSourceRepository infoSourceRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private Clock clock;

    private Long sourceId;

    private PipelineExpressService expressService;

    @BeforeEach
    void setUp() {
        InfoSource source =
                InfoSource.create(
                        "t131e_src",
                        "t131e源",
                        "宏观",
                        AdapterType.RSS,
                        null,
                        "https://example.com/t131e",
                        null,
                        15,
                        true,
                        false);
        infoSourceRepository.save(source);
        sourceId = source.getId();

        // express 装配：真实仓储 + 真实 L0；LLM 段（L1/L2）与护栏/排除面 Mock——并发语义在 L0/仓储层，不依赖 LLM
        ClassificationService classificationService = mock(ClassificationService.class);
        when(classificationService.classifyBatch(anyList()))
                .thenReturn(new ClassificationService.BatchOutcome(0, 0));
        EventExtractionService eventExtractionService = mock(EventExtractionService.class);
        when(eventExtractionService.runL2Window())
                .thenReturn(new EventExtractionService.L2Report(0, 0, 0, 0, 0));
        AiExclusionResolver exclusionResolver = mock(AiExclusionResolver.class);
        when(exclusionResolver.excludedSourceIds(any())).thenReturn(List.of());
        PipelineGuardService guardService = mock(PipelineGuardService.class);
        when(guardService.currentLevel()).thenReturn(GuardLevel.NORMAL);
        SubjectMatcher subjectMatcher = mock(SubjectMatcher.class);
        when(subjectMatcher.match(anyString(), anyString())).thenReturn(List.of());
        RuntimeConfigService configService = mock(RuntimeConfigService.class);
        when(configService.read(anyString())).thenReturn(Optional.empty());
        expressService =
                new PipelineExpressService(
                        repository,
                        l0Prefilter,
                        classificationService,
                        eventExtractionService,
                        subjectMatcher,
                        exclusionResolver,
                        guardService,
                        new PipelineSettings(configService, new ObjectMapper()),
                        new RecommendationSettings(configService, new ObjectMapper()),
                        clock);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM news_analysis WHERE news_id IN "
                        + "(SELECT id FROM news_item WHERE source_id = ?)",
                sourceId);
        jdbcTemplate.update("DELETE FROM news_item WHERE source_id = ?", sourceId);
        jdbcTemplate.update("DELETE FROM info_source WHERE id = ?", sourceId);
    }

    /**
     * 直插高分 news_item（宏观 2.0 + 强触发「降准」2.0 = 4.0 命中 express 阈值；created_at 取 5min 前——同时过 express 30s
     * 与常规 2min 两道缓冲，双 Job 同刻可见）。
     */
    private long insertHighScoreNews(String externalId) {
        String past = Instant.now().minus(java.time.Duration.ofMinutes(5)).toString();
        jdbcTemplate.update(
                "INSERT INTO news_item (source_id, external_id, title, summary, url, published_at,"
                        + " fetched_at, fingerprint, status, created_at, updated_at)"
                        + " VALUES (?, ?, '央行宣布降准0.5个百分点', '摘要',"
                        + " 'https://example.com/t131e/n', ?, ?, ?, 1, ?, ?)",
                sourceId,
                externalId,
                past,
                past,
                "fp-t131e-" + externalId,
                past,
                past);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM news_item WHERE external_id = ?", Long.class, externalId);
    }

    @Test
    void concurrentExpressAndRegularL0_sameItem_singleRowOneWinner() throws Exception {
        // Arrange：同一高分条目同时进入快速通道与常规批视野
        long newsId = insertHighScoreNews("t131e_race_1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> express =
                pool.submit(
                        () -> {
                            start.await();
                            return expressService.tick();
                        });
        Future<?> regular =
                pool.submit(
                        () -> {
                            start.await();
                            return l0Prefilter.run();
                        });

        // Act：同刻起跑（竞争建行）
        start.countDown();
        express.get(30, TimeUnit.SECONDS);
        regular.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Assert：单行收敛（UNIQUE(news_id) INSERT OR IGNORE——一人胜出）；PASS 进 L1 池
        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_analysis WHERE news_id = ?",
                        Integer.class,
                        newsId);
        assertThat(rows).isEqualTo(1);
        assertThat(readL0Result(newsId)).isEqualTo("PASS");
    }

    @Test
    void expressTick_idempotentReTick_noDuplicateRow() {
        // Arrange/Act：同条目 express 连跑两 tick（第二 tick 无未建行候选）
        insertHighScoreNews("t131e_race_2");
        expressService.tick();
        PipelineExpressService.ExpressReport second = expressService.tick();

        // Assert：幂等（第二 tick 对本条目 hit:0）且行唯一
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_analysis na JOIN news_item ni ON ni.id ="
                                + " na.news_id WHERE ni.external_id = 't131e_race_2'",
                        Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(second.detail()).startsWith("express=scan:").contains("hit:0");
    }

    @Test
    void concurrentL1ConditionalUpdate_exactlyOneWins() throws Exception {
        // Arrange：express 建行后，常规批与 express 并发 L1 落库（双路径同刻写 DONE）
        long newsId = insertHighScoreNews("t131e_race_3");
        expressService.tick(); // 建行 PASS/PENDING
        NewsAnalysisRepository.L1Write write =
                new NewsAnalysisRepository.L1Write(
                        newsId, "宏观", null, null, 0.9, false, "[]", "v1.0", Instant.now());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first =
                pool.submit(
                        () -> {
                            start.await();
                            return repository.applyL1Result(write);
                        });
        Future<Integer> second =
                pool.submit(
                        () -> {
                            start.await();
                            return repository.applyL1Result(write);
                        });

        // Act
        start.countDown();
        int updatedFirst = first.get(30, TimeUnit.SECONDS);
        int updatedSecond = second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Assert：条件 UPDATE（WHERE l1_status IN (PENDING,FAILED)）——合计恰好 1 人胜出
        assertThat(updatedFirst + updatedSecond).isEqualTo(1);
        assertThat(readL1Status(newsId)).isEqualTo("DONE");
    }

    private String readL0Result(long newsId) {
        return jdbcTemplate.queryForObject(
                "SELECT l0_result FROM news_analysis WHERE news_id = ?", String.class, newsId);
    }

    private String readL1Status(long newsId) {
        return jdbcTemplate.queryForObject(
                "SELECT l1_status FROM news_analysis WHERE news_id = ?", String.class, newsId);
    }
}
